package com.kling.waic.component.handler

import com.kling.waic.component.entity.ActivityConfigProps
import com.kling.waic.component.entity.Locale
import com.kling.waic.component.service.ImageTaskMode
import com.kling.waic.component.utils.Constants
import com.kling.waic.component.utils.FileUtils
import com.kling.waic.component.utils.ImageUtils
import com.kling.waic.component.utils.Slf4j.Companion.log
import com.kling.waic.component.utils.ThreadContextUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage

abstract class ActivityHandler {

    @Autowired
    private lateinit var activityConfigProps: ActivityConfigProps

    abstract fun activityName(): String

    abstract fun getCanvas(totalWidth: Int, totalHeight: Int): Pair<BufferedImage, Graphics2D>

    abstract fun drawLogoInLeftCorner(
        locale: Locale,
        scaleFactor: Double,
        g2d: Graphics2D,
        logoTopLeftX: Int,
        logoTopLeftY: Int
    )

    abstract fun getImageTaskMode(): ImageTaskMode

    open fun getPrompts(activity: String): List<String> {
        val styleImagePrompts = try {
            FileUtils.readTextFromResourcesAsList("style-image-prompts-${activity}.txt")
        } catch (e: Exception) {
            log.info("getPrompts for specific activity: $activity error, message: ${e.message}")
            FileUtils.readTextFromResourcesAsList("style-image-prompts.txt")
        }
        val taskN = getImageTaskMode().taskN
        log.info("getPrompts taskMode: ${getImageTaskMode().name}, taskN: ${taskN}")
        return styleImagePrompts.shuffled().take(taskN)
    }

    fun getAksk(): Pair<String, String> {
        val activity = ThreadContextUtils.getActivity()
        val activityToUse = activity.ifEmpty {
            Constants.DEFAULT_ACTIVITY
        }

        val activityConfig = activityConfigProps.map[activityToUse]
            ?: throw IllegalStateException("Activity config not found: $activity")
        return Pair(activityConfig.accessKey, activityConfig.secretKey)
    }
}

@Component
class DefaultActivityHandler: ActivityHandler() {

    override fun activityName(): String {
        return Constants.DEFAULT_ACTIVITY
    }

    override fun getCanvas(
        totalWidth: Int,
        totalHeight: Int
    ): Pair<BufferedImage, Graphics2D> {
        val activity = ThreadContextUtils.getActivity()
        val wallpaperImage =
            FileUtils.getImageFromResources("KlingAI-sudoku-background-${activity}.png")
        log.info("wallpaperImage for $activity: $wallpaperImage")
        val canvas = if (wallpaperImage != null) {
            ImageUtils.resizeAndCropToRatio(wallpaperImage, totalWidth, totalHeight)
        } else {
            log.info("not found specific wallpaperImage for $activity, using default wallpaper")
            BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB)
        }
        val g2d: Graphics2D = canvas.createGraphics()

        g2d.color = Color.BLACK
        g2d.fillRect(0, 0, totalWidth, totalHeight)
        return Pair(canvas, g2d)
    }

    override fun drawLogoInLeftCorner(
        locale: Locale,
        scaleFactor: Double,
        g2d: Graphics2D,
        logoTopLeftX: Int,
        logoTopLeftY: Int
    ) {
        val activity = ThreadContextUtils.getActivity()
        val logoImage = FileUtils.convertFileAsImage(
            "KlingAI-sudoku-logo-$locale-$activity.png",
            "KlingAI-sudoku-logo-$locale-default.png",
        )
        val width = logoImage.getWidth(null)
        val height = logoImage.getHeight(null)
        val actualWidth: Double = 18.0 * width / height
        log.debug("actualWidth for corner logo: $actualWidth")

        val logoWidth = (actualWidth * scaleFactor).toInt()
        val logoHeight = (18 * scaleFactor).toInt()
        val scaledLogoImage = logoImage.getScaledInstance(logoWidth, logoHeight, BufferedImage.SCALE_SMOOTH)
        g2d.drawImage(scaledLogoImage, logoTopLeftX, logoTopLeftY, null)
    }

    override fun getImageTaskMode(): ImageTaskMode {
        return ImageTaskMode.WITH_ORIGIN
    }
}

@Component
class XiaozhaoActivityHandler: DefaultActivityHandler() {

    override fun activityName(): String {
        return "xiaozhao"
    }

    override fun getImageTaskMode(): ImageTaskMode {
        return ImageTaskMode.ALL_GENERATED_FIXED_CENTER
    }

    override fun getPrompts(activity: String): List<String> {
        val styleImagePromptsForXiaozhao =
            FileUtils.readTextFromResourcesAsList("style-image-prompts-${activity}.txt")
        log.debug("prompts for xiaozhao: ${styleImagePromptsForXiaozhao}")

        val taskN = getImageTaskMode().taskN
        val centerPrompt = styleImagePromptsForXiaozhao.first()
        val surroundingPrompts = styleImagePromptsForXiaozhao.drop(1)
            .shuffled()
            .take(taskN - 1)

        val middleIndex = taskN / 2
        return surroundingPrompts.toMutableList().apply {
            add(middleIndex, centerPrompt)
        }
    }
}