package app.darksquare.blindfold.domain.repo

import kotlinx.coroutines.flow.Flow

interface SttRepository {
    /** Begin listening. Cold flow — collecting starts the recognizer; cancelling stops it. */
    fun listen(): Flow<SttEvent>
}

sealed interface SttEvent {
    data object ReadyForSpeech : SttEvent
    data object BeginningOfSpeech : SttEvent
    data class Partial(val text: String) : SttEvent
    data class Final(val candidates: List<String>) : SttEvent
    data class Error(val code: Int, val message: String) : SttEvent
}
