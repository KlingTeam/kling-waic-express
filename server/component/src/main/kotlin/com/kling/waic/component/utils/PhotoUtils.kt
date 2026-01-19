package com.kling.waic.component.utils

import com.kling.waic.component.entity.Printing
import com.kling.waic.component.utils.Slf4j.Companion.log
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.imageio.ImageIO

object PhotoUtils {

    fun generateBatchAsOnePdf(
        printings: List<Printing>,
        outputPath: String,
        extraScaleFactor: Float,
        drawImageX: Float,
        drawImageY: Float,
        pageWidthInches: Float = 6f,
        pageHeightInches: Float = 4f,
    ): String {
        val pageWidthPt = pageWidthInches * 72f * extraScaleFactor
        val pageHeightPt = pageHeightInches * 72f * extraScaleFactor
        val pageSize = PDRectangle(pageWidthPt, pageHeightPt)

        PDDocument().use { doc ->
            printings.forEachIndexed { index, printing ->
                try {
                    val page = PDPage(pageSize)
                    doc.addPage(page)

                    val imageUrl = printing.task.outputs?.url
                        ?: throw IllegalArgumentException("No output URL for printing ${printing.name}")
                    val bufferedImage = downloadImageFromUrl(imageUrl)
                    val processedImage = processImageForPdf(bufferedImage)
                    val pdfImage = convertBufferedImageToPDImage(processedImage, doc)

                    PDPageContentStream(doc, page).use { contentStream ->
                        contentStream.drawImage(pdfImage, drawImageX, drawImageY, pageWidthPt, pageHeightPt)
                    }

                } catch (e: Exception) {
                    log.error("Failed to process photo ${index + 1} (${printing.name}): ${e.message}", e)
                    throw e
                }
            }

            doc.save(File(outputPath))
        }

        log.info("✅ PDF generated successfully: $outputPath")
        return outputPath
    }

    private fun processImageForPdf(originalImage: BufferedImage): BufferedImage {
        val width = originalImage.width
        val height = originalImage.height
        val isPortrait = height > width
        
        log.debug("Original image: ${width}x${height}, isPortrait: $isPortrait")
        return if (isPortrait) {
            log.info("Rotating portrait image 90 degrees clockwise")
            rotate90(originalImage)
        } else {
            log.info("Image is already landscape, no rotation needed")
            originalImage
        }
    }

    private fun rotate90(original: BufferedImage): BufferedImage {
        val w = original.width
        val h = original.height
        val rotated = BufferedImage(h, w, original.type)
        for (x in 0 until w) {
            for (y in 0 until h) {
                rotated.setRGB(h - y - 1, x, original.getRGB(x, y))
            }
        }
        return rotated
    }

    private fun downloadImageFromUrl(imageUrl: String): BufferedImage {
        return URL(imageUrl).openStream().use { inputStream ->
            ImageIO.read(inputStream)
                ?: throw IllegalArgumentException("Failed to read image from URL: $imageUrl")
        }
    }

    private fun convertBufferedImageToPDImage(bufferedImage: BufferedImage, document: PDDocument): PDImageXObject {
        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "jpg", baos)
        val imageBytes = baos.toByteArray()

        return ByteArrayInputStream(imageBytes).use { inputStream ->
            PDImageXObject.createFromByteArray(document, imageBytes, "image")
        }
    }

    fun generateTempPdfPath(printings: List<Printing>): String {
        val timestamp = System.currentTimeMillis()
        val names = printings.take(2).joinToString("-") { it.name }
        return "/tmp/temp_pdf_${names}_${timestamp}.pdf"
    }
}