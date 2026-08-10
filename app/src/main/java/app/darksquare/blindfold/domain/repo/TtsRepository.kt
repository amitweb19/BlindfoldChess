package app.darksquare.blindfold.domain.repo

import kotlinx.coroutines.flow.Flow

interface TtsRepository {
    /** Lifecycle. Must be called from a context with access to AppContext. */
    suspend fun warmUp()
    fun shutdown()

    /**
     * Speak a SAN move converted to natural English.
     * Example: "Qxd4+" -> "Queen takes D four, check"
     */
    suspend fun speakMove(san: String, interrupt: Boolean = true)

    /** Speak arbitrary text (eval read-out, clock, prompts). */
    suspend fun speak(text: String, interrupt: Boolean = true)

    /** Hot flow of TTS lifecycle events for UI affordances. */
    val events: Flow<TtsEvent>
}

sealed interface TtsEvent {
    data object Started : TtsEvent
    data object Done : TtsEvent
    data class Error(val message: String) : TtsEvent
}
