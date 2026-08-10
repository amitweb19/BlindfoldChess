package app.darksquare.blindfold.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.darksquare.blindfold.data.UserPhonemeStore
import app.darksquare.blindfold.domain.repo.SttEvent
import app.darksquare.blindfold.domain.repo.SttRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalibState(
    val index: Int = 0,
    val total: Int = 0,
    val display: String = "",
    val canonical: String = "",
    val heard: String = "",
    val isListening: Boolean = false,
    val isDone: Boolean = false
)

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val stt: SttRepository,
    private val store: UserPhonemeStore
) : ViewModel() {

    private val words = listOf(
        "KNIGHT"   to "knight", "BISHOP"  to "bishop",
        "ROOK"     to "rook",   "QUEEN"   to "queen",
        "KING"     to "king",   "PAWN"    to "pawn",
        "Letter F" to "f",      "Letter G" to "g",
        "Letter H" to "h",      "THREE"   to "3",
        "FOUR"     to "4",      "FIVE"    to "5",
        "SIX"      to "6",      "SEVEN"   to "7",
        "TAKES"    to "takes",  "CASTLE"  to "castle"
    )

    private val captured = mutableMapOf<String, String>()

    private val _state = MutableStateFlow(
        CalibState(display = words[0].first, canonical = words[0].second, total = words.size)
    )
    val state = _state.asStateFlow()

    fun startListening() {
        if (_state.value.isListening) return
        _state.value = _state.value.copy(isListening = true, heard = "")
        viewModelScope.launch {
            val ev = stt.listen().first { it is SttEvent.Final || it is SttEvent.Error }
            val heard = (ev as? SttEvent.Final)?.candidates?.firstOrNull()?.lowercase()?.trim() ?: ""
            _state.value = _state.value.copy(isListening = false, heard = heard)
        }
    }

    /** Accept the current heard text (stores mapping if it differs from canonical). */
    fun accept() {
        val s = _state.value
        if (s.heard.isNotBlank() && s.heard != s.canonical) {
            captured[s.heard] = s.canonical
        }
        advance()
    }

    fun skip() = advance()

    private fun advance() {
        val next = _state.value.index + 1
        if (next >= words.size) {
            val merged = store.getMappings().toMutableMap()
            merged.putAll(captured)
            store.saveMappings(merged)
            captured.forEach { (spoken, mapped) ->
                store.appendHistory(
                    spoken = spoken,
                    mapped = mapped,
                    category = inferCategory(mapped),
                    source = "calibration"
                )
            }
            store.markCalibrated()
            _state.value = _state.value.copy(isDone = true)
        } else {
            _state.value = CalibState(
                index    = next,
                total    = words.size,
                display  = words[next].first,
                canonical = words[next].second
            )
        }
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
}
