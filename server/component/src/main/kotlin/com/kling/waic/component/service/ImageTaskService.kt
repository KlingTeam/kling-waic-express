package com.kling.waic.component.service

import com.kling.waic.component.entity.Locale
import com.kling.waic.component.entity.OpenApiRecord
import com.kling.waic.component.entity.TaskStatus
import com.kling.waic.component.entity.TaskType
import com.kling.waic.component.exception.KlingOpenAPIException
import com.kling.waic.component.exception.TooManyRequestException
import com.kling.waic.component.external.KlingOpenAPIClient
import com.kling.waic.component.external.model.*
import com.kling.waic.component.helper.ImageProcessHelper
import com.kling.waic.component.repository.LockRepository
import com.kling.waic.component.repository.TaskRepository
import com.kling.waic.component.selector.ActivityHandlerSelector
import com.kling.waic.component.utils.ObjectMapperUtils
import com.kling.waic.component.utils.Slf4j.Companion.log
import com.kling.waic.component.utils.ThreadContextUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

enum class ImageTaskMode(val taskN: Int) {
    ALL_GENERATED(9),
    WITH_ORIGIN(8),
    ALL_GENERATED_FIXED_CENTER(9)
}

@Service
class ImageTaskService(
    private val klingOpenAPIClient: KlingOpenAPIClient,
    private val imageProcessHelper: ImageProcessHelper,
    private val taskRepository: TaskRepository,
    private val lockRepository: LockRepository,
    @Value("\${IMAGE_TASK_CONCURRENCY:90}")
    private val imageTaskConcurrency: Int,
    private val activityHandlerSelector: ActivityHandlerSelector
) : TaskService() {

    override suspend fun doCreateTask(requestImageUrl: String): List<OpenApiRecord> {
        val activityHandler = activityHandlerSelector.selectActivityHandler()
        val imageTaskMode = activityHandler.getImageTaskMode()
        val prompts = activityHandler.getPrompts(ThreadContextUtils.getActivity())
        val taskN = imageTaskMode.taskN

        // 因为 executeWithLock 是同步方法，所以这里只能用 runBlocking 包住挂起逻辑
        val openApiRecords = lockRepository.executeWithLock(
            lockKey = "doCreateTask_${TaskType.STYLED_IMAGE}",
            action = {
                runBlocking {
                    // 在锁内检查并发限制，确保互斥访问
                    checkConcurrency(taskN)

                    val deferredResults = prompts.mapIndexed { index, prompt ->
                        async {
                            val request = CreateImageTaskRequest(
                                image = requestImageUrl,
                                prompt = prompt
                            )

                            val result = klingOpenAPIClient.createImageTask(request)
                            if (result.code != 0) {
                                throw KlingOpenAPIException(result)
                            }
                            log.info(
                                "Create Image Task with image: $requestImageUrl, " +
                                        "prompt: $prompt, taskId: ${result.data?.taskId}"
                            )
                            result.data?.taskId?.let { taskId ->
                                OpenApiRecord(
                                    taskId = taskId,
                                    promptIndex = index,
                                    prompt = prompt,
                                    inputImage = requestImageUrl,
                                )
                            }
                        }
                    }

                    deferredResults.awaitAll().filterNotNull()
                }
            }
        )

        if (openApiRecords.size != taskN) {
            throw IllegalStateException(
                "Failed to create the expected number of tasks: $taskN, " +
                        "only created ${openApiRecords.size} tasks."
            )
        }
        return openApiRecords
    }

    override suspend fun doQueryTask(taskIds: List<String>, taskName: String): Pair<TaskStatus, QueryTaskContext> {
        // Use coroutineScope with current context to preserve thread
        val taskResponseMap = coroutineScope {
            val queryTaskRequests = taskIds.map { taskId ->
                // Use current context instead of Dispatchers.IO
                async {
                    val request = QueryImageTaskRequest(taskId = taskId)

                    val result = klingOpenAPIClient.queryImageTask(request)
                    if (result.code != 0) {
                        throw KlingOpenAPIException(result)
                    }
                    log.debug(
                        "Query Image Task with result, taskId: {}, taskStatus: {}, result: {}",
                        result.data?.taskId, result.data?.taskStatus,
                        ObjectMapperUtils.toJSON(result)
                    )
                    if (result.data?.taskStatus == KlingOpenAPITaskStatus.failed) {
                        log.error("Image Task failed, taskId: $taskId, " +
                                "result: ${ObjectMapperUtils.toJSON(result)}")
                    }
                    taskId to result.data
                }
            }

            // Wait for all deferred results to complete
            val results = queryTaskRequests.awaitAll()
            mutableMapOf<String, QueryImageTaskResponse>().apply {
                results.forEach { (taskId, data) ->
                    data?.let { this[taskId] = it }
                }
            }
        }

        val summaryMap = summaryResponse(taskResponseMap)
        val overallStatus = calculateOverallStatus(summaryMap, taskIds.size)
        log.info("Image Task $taskName overallStatus: $overallStatus, " +
                "summaryInfo: ${summaryInfo(overallStatus, summaryMap, taskIds.size)}")
        return Pair(overallStatus, QueryTaskContext(taskResponseMap = taskResponseMap))
    }

    override suspend fun generateOutputUrl(
        taskName: String,
        queryTaskContext: QueryTaskContext,
        locale: Locale
    ): Pair<String, String> {
        log.debug("Generating Sudoku image URL for task: $taskName")
        val taskResponseMap = queryTaskContext.taskResponseMap

        val imageUrls: MutableList<String> = taskResponseMap.values
            .flatMap { it.taskResult.images ?: emptyList() }
            .map { it.url.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        val activityHandler = activityHandlerSelector.selectActivityHandler()
        val imageTaskMode = activityHandler.getImageTaskMode()
        if (imageTaskMode == ImageTaskMode.WITH_ORIGIN) {
            // add origin image to the center of imageUrls.
            val task = taskRepository.getTask(taskName)
                ?: throw IllegalStateException("Task with name $taskName not found")
            val requestImageUrl = task.input.image

            // calculate middle index of the list
            val middleIndex = imageUrls.size / 2
            imageUrls.add(middleIndex, requestImageUrl)
        }

        val outputImageUrlPair = imageProcessHelper.downloadAndCreateSudoku(
            taskName,
            imageUrls,
            locale
        )
        return outputImageUrlPair
    }

    private fun summaryResponse(taskResponseMap: Map<String, QueryImageTaskResponse>):
            MutableMap<KlingOpenAPITaskStatus, MutableList<String>> {
        val summaryMap = mutableMapOf<KlingOpenAPITaskStatus, MutableList<String>>()
        taskResponseMap.forEach { taskId, response ->
            summaryMap.computeIfAbsent(response.taskStatus) { mutableListOf() }.add(taskId)
        }
        return summaryMap
    }

    private fun calculateOverallStatus(
        summaryMap: MutableMap<KlingOpenAPITaskStatus, MutableList<String>>,
        totalCount: Int
    ): TaskStatus {
        return when {
            (summaryMap[KlingOpenAPITaskStatus.submitted]?.size
                ?: 0) == totalCount -> TaskStatus.SUBMITTED

            (summaryMap[KlingOpenAPITaskStatus.succeed]?.size
                ?: 0) == totalCount -> TaskStatus.SUCCEED

            !summaryMap[KlingOpenAPITaskStatus.failed].isNullOrEmpty() -> TaskStatus.FAILED
            else -> TaskStatus.PROCESSING
        }
    }

    private fun summaryInfo(
        overallStatus: TaskStatus,
        summaryMap: MutableMap<KlingOpenAPITaskStatus, MutableList<String>>,
        totalCount: Int
    ): String {
        return when (overallStatus) {
            TaskStatus.SUBMITTED -> "All tasks are submitted: $totalCount."
            TaskStatus.SUCCEED -> "All tasks succeeded: $totalCount."
            TaskStatus.FAILED -> "Some tasks failed: " +
                    "${summaryMap[KlingOpenAPITaskStatus.failed]?.size ?: 0} / $totalCount, " +
                    "failed taskIds: ${summaryMap[KlingOpenAPITaskStatus.failed]?.joinToString(", ") ?: "none"}."

            TaskStatus.PROCESSING -> "Tasks are still processing, " +
                    "submitted: ${summaryMap[KlingOpenAPITaskStatus.submitted]?.size ?: 0} / $totalCount, " +
                    "processing: ${summaryMap[KlingOpenAPITaskStatus.processing]?.size ?: 0} / $totalCount, " +
                    "succeed: ${summaryMap[KlingOpenAPITaskStatus.succeed]?.size ?: 0} / $totalCount, " +
                    "submitted taskIds: ${summaryMap[KlingOpenAPITaskStatus.submitted]?.joinToString(", ") ?: "none"}."
        }
    }

    private fun checkConcurrency(taskN: Int) {
        val getCurrentConcurrencyRequest = GetCurrentConcurrencyRequest(
            budgetType = BudgetType.image
        )
        val response = klingOpenAPIClient.getCurrentConcurrency(getCurrentConcurrencyRequest)
        val currentConcurrency = response.data!!
        log.info("Current concurrency for ${TaskType.STYLED_IMAGE}: ${currentConcurrency}")

        val availableConcurrency = imageTaskConcurrency - currentConcurrency
        if (availableConcurrency < taskN) {
            throw TooManyRequestException(
                "Too many requests, " +
                        "availableConcurrency: $availableConcurrency, taskN: $taskN")
        }
    }
}