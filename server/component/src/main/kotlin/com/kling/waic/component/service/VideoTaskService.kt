package com.kling.waic.component.service

import com.kling.waic.component.entity.Locale
import com.kling.waic.component.entity.OpenApiRecord
import com.kling.waic.component.entity.TaskStatus
import com.kling.waic.component.exception.KlingOpenAPIException
import com.kling.waic.component.external.KlingOpenAPIClient
import com.kling.waic.component.external.model.CreateVideoTaskInput
import com.kling.waic.component.external.model.CreateVideoTaskRequest
import com.kling.waic.component.external.model.KlingOpenAPITaskStatus
import com.kling.waic.component.external.model.QueryTaskContext
import com.kling.waic.component.external.model.QueryVideoTaskRequest
import com.kling.waic.component.helper.VideoResizeHelper
import com.kling.waic.component.utils.FileUtils
import com.kling.waic.component.utils.ObjectMapperUtils
import com.kling.waic.component.utils.Slf4j.Companion.log
import com.kling.waic.component.utils.ThreadContextUtils
import org.springframework.stereotype.Service

@Service
class VideoTaskService(
    private val klingOpenAPIClient: KlingOpenAPIClient,
    private val videoResizeHelper: VideoResizeHelper
) : TaskService() {

    override suspend fun doCreateTask(requestImageUrl: String): List<OpenApiRecord> {
        val videoSpecialEffects = getSpecialEffects(ThreadContextUtils.getActivity())
        val effectScene = videoSpecialEffects.random()
        val request = CreateVideoTaskRequest(
            effectScene = effectScene,
            input = CreateVideoTaskInput(
                image = requestImageUrl
            )
        )
        val result = klingOpenAPIClient.createVideoTask(request)
        if (result.code != 0) {
            throw KlingOpenAPIException(result)
        }
        log.info("Create Video Task with effectScene: $effectScene, taskId: ${result.data?.taskId}")
        return listOf(OpenApiRecord(
            taskId = result.data!!.taskId,
            inputImage = requestImageUrl
        ))
    }

    override suspend fun doQueryTask(
        taskIds: List<String>,
        taskName: String
    ): Pair<TaskStatus, QueryTaskContext> {
        val taskId = taskIds.first()
        val request = QueryVideoTaskRequest(taskId)
        val result = klingOpenAPIClient.queryVideoTask(request)
        if (result.code != 0) {
            throw KlingOpenAPIException(result)
        }
        log.debug(
            "Query Video Task with result, taskId: {}, taskStatus: {}, result: {}",
            taskId,
            result.data?.taskStatus ?: "null",
            ObjectMapperUtils.toJSON(result)
        )
        if (result.data?.taskStatus == KlingOpenAPITaskStatus.failed) {
            log.error("Video Task failed, taskId: $taskId, " +
                    "result: ${ObjectMapperUtils.toJSON(result)}")
        }

        val taskStatus = result.data!!.taskStatus
        val convertedStatus = calculateStatus(taskStatus)
        log.info("Video Task $taskName convertedStatus: $convertedStatus")

        val video = result.data.taskResult.videos?.firstOrNull()
        return Pair(convertedStatus, QueryTaskContext(video = video))
    }

    override suspend fun generateOutputUrl(
        taskName: String,
        queryTaskContext: QueryTaskContext,
        locale: Locale
    ): Pair<String, String> {
        val url = queryTaskContext.video!!.url
        val thumbnailUrl = videoResizeHelper.resizeVideoByUrl(
            taskName = taskName,
            inputUrl = url,
            targetWidth = 225,
            targetHeight = 400
        )
        return Pair(url, thumbnailUrl)
    }

    private fun calculateStatus(taskStatus: KlingOpenAPITaskStatus): TaskStatus {
        return when (taskStatus) {
            KlingOpenAPITaskStatus.submitted -> TaskStatus.SUBMITTED
            KlingOpenAPITaskStatus.processing -> TaskStatus.PROCESSING
            KlingOpenAPITaskStatus.succeed -> TaskStatus.SUCCEED
            KlingOpenAPITaskStatus.failed -> TaskStatus.FAILED
        }
    }

    private fun getSpecialEffects(activity: String): List<String> {
        return try {
            FileUtils.readTextFromResourcesAsList("video-special-effects-${activity}.txt")
        } catch (e: Exception) {
            log.info("getSpecialEffects for specific activity: $activity error, message: ${e.message}")
            FileUtils.readTextFromResourcesAsList("video-special-effects.txt")
        }
    }
}