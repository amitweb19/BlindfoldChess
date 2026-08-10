package app.darksquare.blindfold.ui.game

/**
 * Central engine difficulty mapping so UI labels and engine behavior stay in sync.
 */
object EngineDifficulty {
    val ratingDepthTable: List<Pair<Int, Int>> = listOf(
        1000 to 4,
        1200 to 5,
        1400 to 6,
        1600 to 7,
        1800 to 8,
        2000 to 9,
        2200 to 10,
        2400 to 11,
        2600 to 12,
        2800 to 13,
        3000 to 14,
    )

    fun depthForRating(rating: Int): Int {
        val clamped = rating.coerceIn(ratingDepthTable.first().first, ratingDepthTable.last().first)
        return ratingDepthTable.lastOrNull { clamped >= it.first }?.second ?: ratingDepthTable.first().second
    }

    fun optionLabel(rating: Int): String = "$rating (Depth ${depthForRating(rating)})"

    fun parseRatingFromOption(option: String): Int = option.substringBefore(' ').toIntOrNull() ?: 1800
}
