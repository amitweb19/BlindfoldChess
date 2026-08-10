package app.darksquare.blindfold.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class MappingHistoryEntry(
    val timestampMs: Long,
    val spoken: String,
    val mapped: String,
    val category: String,
    val source: String,
)

/**
 * Persists user-calibrated phoneme mappings so VoiceMoveParser can map
 * this user's STT output (e.g. "gee") to the canonical chess token ("g").
 */
@Singleton
class UserPhonemeStore @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("user_phonemes", Context.MODE_PRIVATE)
    private val historyKey = "mapping_history"

    fun isCalibrated(): Boolean = prefs.getBoolean("calibrated", false)

    fun markCalibrated() = prefs.edit().putBoolean("calibrated", true).apply()

    /** Returns the stored sttOutput→canonicalChessToken map. */
    fun getMappings(): Map<String, String> =
        (prefs.getStringSet("mappings", emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                val i = entry.indexOf(":::")
                if (i > 0) entry.substring(0, i) to entry.substring(i + 3) else null
            }.toMap()

    fun saveMappings(mappings: Map<String, String>) =
        prefs.edit()
            .putStringSet("mappings", mappings.map { (k, v) -> "$k:::$v" }.toSet())
            .apply()

    fun appendHistory(
        spoken: String,
        mapped: String,
        category: String,
        source: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val row = listOf(
            timestampMs.toString(),
            source.trim(),
            category.trim(),
            spoken.trim(),
            mapped.trim(),
        ).joinToString(":::")

        val existing = prefs.getStringSet(historyKey, emptySet()) ?: emptySet()
        val next = (existing + row).toList().takeLast(400).toSet()
        prefs.edit().putStringSet(historyKey, next).apply()
    }

    fun getHistory(limit: Int = 200): List<MappingHistoryEntry> {
        val explicit = (prefs.getStringSet(historyKey, emptySet()) ?: emptySet())
            .mapNotNull { parseHistoryRow(it) }
            .sortedByDescending { it.timestampMs }
        val userMappings = getMappings().toSortedMap().map { (spoken, mapped) ->
            MappingHistoryEntry(0L, spoken, mapped, inferCategory(mapped), "user-mapping")
        }
        val builtin = BUILTIN_VOCAB.map { (spoken, mapped) ->
            MappingHistoryEntry(0L, spoken, mapped, inferCategory(mapped), "built-in")
        }
        return (explicit + userMappings + builtin).distinctBy { it.spoken + it.mapped }.take(limit)
    }

    private fun parseHistoryRow(row: String): MappingHistoryEntry? {
        val p = row.split(":::")
        if (p.size < 5) return null
        val ts = p[0].toLongOrNull() ?: return null
        return MappingHistoryEntry(
            timestampMs = ts,
            source = p[1],
            category = p[2],
            spoken = p[3],
            mapped = p[4],
        )
    }

    private fun inferCategory(mapped: String): String {
        val m = mapped.lowercase().trim()
        return when {
            m in setOf("pawn", "knight", "bishop", "rook", "queen", "king") -> "piece"
            m.length == 1 && m[0] in 'a'..'h' -> "file"
            m.length == 1 && m[0] in '1'..'8' -> "rank"
            m.matches(Regex("^[a-h][1-8]$")) -> "square"
            m.matches(Regex("^(pawn|knight|bishop|rook|queen|king) [a-h][1-8]$")) -> "move_phrase"
            else -> "other"
        }
    }

    private companion object {
        val BUILTIN_VOCAB: List<Pair<String, String>> = listOf(
            "night" to "knight", "nite" to "knight", "knife" to "knight", "horse" to "knight",
            "be shop" to "bishop", "bee shop" to "bishop",
            "rock" to "rook", "root" to "rook",
            "pun" to "pawn", "porn" to "pawn", "prawn" to "pawn",
            "ate" to "8", "tree" to "3",
            "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
            "ef" to "f", "gee" to "g",
            "aitch" to "h", "eitch" to "h", "haitch" to "h",
            "ay" to "a", "ee" to "e", "bee" to "b",
            "see" to "c", "sea" to "c", "dee" to "d",
        )
    }
}
