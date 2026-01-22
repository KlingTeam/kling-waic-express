package com.kling.waic.api.helper

import SpringBaseTest
import com.kling.waic.component.entity.Locale
import com.kling.waic.component.helper.ImageProcessHelper
import com.kling.waic.component.utils.FileUtils
import com.kling.waic.component.utils.ThreadContextUtils
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO


class ImageProcessHelperTest: SpringBaseTest() {

    @Autowired
    private lateinit var imageProcessHelper: ImageProcessHelper

    @Test
    fun test() {
//        ThreadContextUtils.putActivity("yuanshi")
        ThreadContextUtils.putActivity("guanghe")
//        ThreadContextUtils.putActivity("xiaozhao")

        val taskName = "testTask"
        val imageResource = FileUtils.getImageFromResources("test_girl.png")!!
        val images = mutableListOf<BufferedImage>()
        for (i in 1..9) {
            images.add(imageResource)
        }

        val locale = Locale.CN
        val result = imageProcessHelper.createKlingWAICSudokuImage(taskName, images, locale)


        val output: File = File("output.png")
        ImageIO.write(result, "png", output)

        println(result)
    }
}