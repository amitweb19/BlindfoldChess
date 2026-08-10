package app.darksquare.blindfold.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.darksquare.blindfold.domain.repo.TtsEvent
import app.darksquare.blindfold.domain.repo.TtsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTtsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) : TtsRepository {

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<TtsEvent> = _events
    private var tts: TextToSpeech? = null

    override suspend fun warmUp() {
        val ready = CompletableDeferred<Unit>()
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { _events.tryEmit(TtsEvent.Started) }
                    override fun onDone(id: String?)  { _events.tryEmit(TtsEvent.Done) }
                    @Deprecated("legacy")
                    override fun onError(id: String?) { _events.tryEmit(TtsEvent.Error("tts error")) }
                })
                ready.complete(Unit)
            } else ready.completeExceptionally(IllegalStateException("TTS init failed: $status"))
        }
        ready.await()
    }

    override fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }

    override suspend fun speakMove(san: String, interrupt: Boolean) =
        speak(SanToSpeech.toEnglish(san), interrupt)

    override suspend fun speak(text: String, interrupt: Boolean) {
        val engine = tts ?: return
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, UUID.randomUUID().toString())
    }
}

object SanToSpeech {
    private val pieceNames = mapOf(
        'K' to "King", 'Q' to "Queen", 'R' to "Rook", 'B' to "Bishop", 'N' to "Knight"
    )

    fun toEnglish(san: String): String {
        if (san == "O-O") return "Castles kingside"
        if (san == "O-O-O") return "Castles queenside"

        val sb = StringBuilder()
        val core = san.trimEnd('+', '#', '!', '?')
        val isCheck = san.endsWith("+")
        val isMate  = san.endsWith("#")

        val first = if (core.length > 0) core[0] else null
        var i = 0
        if (first != null && pieceNames.containsKey(first)) {
            sb.append(pieceNames[first]).append(' ')
            i = 1
        } else {
            sb.append("Pawn ")
        }

        // Find the last file character ('a'..'h') which marks the start of the target square
        var targetFileIdx = -1
        for (j in (core.length - 1) downTo 0) {
            if (core[j] in 'a'..'h') {
                targetFileIdx = j
                break
            }
        }

        if (targetFileIdx != -1) {
            while (i < targetFileIdx) {
                val c = core[i]
                when {
                    c == 'x'      -> sb.append("takes ")
                    c in '1'..'8' -> sb.append(numberWord(c)).append(' ')
                    else          -> sb.append(c.uppercaseChar()).append(' ')
                }
                i++
            }
            
            val file = java.lang.Character.toUpperCase(core[targetFileIdx])
            sb.append(file).append(' ')
            i = targetFileIdx + 1
            
            if (i < core.length && core[i] in '0'..'9') {
                sb.append(numberWord(core[i]))
                i++
            }
        }

        if (i < core.length && core[i] == '=') {
            i++
            if (i < core.length) {
                val p = core[i]
                sb.append(" promotes to ").append(pieceNames[java.lang.Character.toUpperCase(p)] ?: "")
            }
        }
        
        if (isMate) sb.append(", checkmate")
        else if (isCheck) sb.append(", check")
        
        return sb.toString().trim()
    }

    private fun Char.isDestStart() = this in 'a'..'h'
    private fun numberWord(c: Char) = when (c) {
        '1' -> "one"; '2' -> "two"; '3' -> "three"; '4' -> "four"
        '5' -> "five"; '6' -> "six"; '7' -> "seven"; '8' -> "eight"
        else -> c.toString()
    }

    /** "+150" centipawns -> "Plus one point five". */
    fun evalToEnglish(cp: Int?, mateIn: Int?): String = when {
        mateIn != null && mateIn > 0 -> "Mate in $mateIn"
        mateIn != null && mateIn < 0 -> "Getting mated in ${-mateIn}"
        cp == null -> "Evaluation unavailable"
        else -> {
            val pawns = cp / 100.0
            val sign = if (pawns >= 0) "plus" else "minus"
            val mag = "%.1f".format(kotlin.math.abs(pawns))
            "$sign $mag"
        }
    }
}
