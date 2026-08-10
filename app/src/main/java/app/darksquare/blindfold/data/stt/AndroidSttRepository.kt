package app.darksquare.blindfold.data.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.darksquare.blindfold.domain.repo.SttEvent
import app.darksquare.blindfold.domain.repo.SttRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSttRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) : SttRepository {

    override fun listen(): Flow<SttEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { trySend(SttEvent.ReadyForSpeech) }
            override fun onBeginningOfSpeech() { trySend(SttEvent.BeginningOfSpeech) }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                trySend(SttEvent.Error(error, "stt error $error"))
                close()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                trySend(SttEvent.Final(list))
                close()
            }
            override fun onPartialResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SttEvent.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
        awaitClose {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
    }.flowOn(Dispatchers.Main)
}
