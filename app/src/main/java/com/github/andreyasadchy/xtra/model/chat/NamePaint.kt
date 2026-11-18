package com.github.andreyasadchy.xtra.model.chat

class NamePaint(
    val id: String? = null,
    val type: String? = null,
    val colors: IntArray? = null,
    val colorPositions: FloatArray? = null,
    val imageUrl: String? = null,
    val angle: Int? = null,
    val repeat: Boolean? = null,
    val shadows: List<Shadow>? = null,
    val imageFit: String? = null,
    val tiles: Int? = null,
    val animation: Animation? = null,
) {

    class Animation(val kind: String, val durationMs: Int)

    class Shadow(
        val xOffset: Float,
        val yOffset: Float,
        val radius: Float,
        val color: Int,
    )
}
