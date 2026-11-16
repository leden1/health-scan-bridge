package com.healthscan.app.ocr

data class OcrToken(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val width: Int get() = (right - left).coerceAtLeast(1)
}

data class OcrLine(
    val text: String,
    val tokens: List<OcrToken>,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val cy: Int get() = (top + bottom) / 2
    val cx: Int get() = (left + right) / 2

    fun overlapsHorizontally(other: OcrLine): Boolean {
        val overlap = minOf(right, other.right) - maxOf(left, other.left)
        val narrowest = minOf(right - left, other.right - other.left)
        return overlap > 0 && narrowest > 0 && overlap.toFloat() / narrowest > 0.25f
    }

    fun overlapsHorizontally(token: OcrToken): Boolean {
        val overlap = minOf(right, token.right) - maxOf(left, token.left)
        return overlap > 0
    }
}

data class OcrResult(
    val lines: List<OcrLine>,
    val imageWidth: Int,
    val imageHeight: Int,
    val tiles: Int,
) {
    val meanConfidence: Float
        get() {
            val tokens = lines.flatMap { it.tokens }
            if (tokens.isEmpty()) return 0f
            return tokens.map { it.confidence }.average().toFloat()
        }
}
