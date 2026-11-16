package com.healthscan.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs on-device ML Kit text recognition over tall screenshots by splitting them into overlapping
 * tiles, then merges the tile output back into one sorted line list in full-image coordinates.
 */
class OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, onTile: (Int, Int) -> Unit = { _, _ -> }): OcrResult {
        val width = bitmap.width
        val height = bitmap.height
        val tiles = tileRanges(height)
        val collected = ArrayList<OcrLine>(512)

        for ((index, range) in tiles.withIndex()) {
            onTile(index + 1, tiles.size)
            val tileHeight = (range.last - range.first + 1).coerceAtMost(height)
            val tile = Bitmap.createBitmap(bitmap, 0, range.first, width, tileHeight)
            try {
                val text = recognizeTile(tile)
                collectLines(text, range.first, collected)
            } finally {
                if (tile !== bitmap) tile.recycle()
            }
        }

        val merged = dedupeLines(collected).sortedWith(compareBy({ it.top }, { it.left }))
        return OcrResult(lines = merged, imageWidth = width, imageHeight = height, tiles = tiles.size)
    }

    fun close() {
        recognizer.close()
    }

    private suspend fun recognizeTile(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
            .addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
            .addOnCanceledListener { if (cont.isActive) cont.resumeWithException(IllegalStateException("OCR cancelled")) }
    }

    private fun collectLines(text: Text, offsetY: Int, out: MutableList<OcrLine>) {
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val tokens = line.elements.mapNotNull { element ->
                    val rect = element.boundingBox ?: return@mapNotNull null
                    OcrToken(
                        text = element.text,
                        left = rect.left,
                        top = rect.top + offsetY,
                        right = rect.right,
                        bottom = rect.bottom + offsetY,
                        confidence = safeConfidence(element.confidence),
                    )
                }
                if (tokens.isEmpty()) continue
                out += OcrLine(
                    text = line.text.trim(),
                    tokens = tokens,
                    left = box.left,
                    top = box.top + offsetY,
                    right = box.right,
                    bottom = box.bottom + offsetY,
                    confidence = safeConfidence(line.confidence),
                )
                if (out.size >= MAX_LINES) return
            }
        }
    }

    private fun dedupeLines(lines: List<OcrLine>): List<OcrLine> {
        val byKey = LinkedHashMap<String, OcrLine>()
        for (line in lines) {
            val key = "${line.text.lowercase()}|${line.top / 12}|${line.left / 24}"
            val existing = byKey[key]
            if (existing == null || line.confidence > existing.confidence) byKey[key] = line
        }
        return byKey.values.toList()
    }

    private fun tileRanges(height: Int): List<IntRange> {
        if (height <= TILE_HEIGHT) return listOf(0 until height)
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < height) {
            val end = (start + TILE_HEIGHT).coerceAtMost(height)
            ranges += start until end
            if (end >= height) break
            start = end - OVERLAP
        }
        return ranges
    }

    private fun safeConfidence(value: Float): Float = if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)

    companion object {
        private const val TILE_HEIGHT = 1400
        private const val OVERLAP = 200
        private const val MAX_LINES = 4000
    }
}
