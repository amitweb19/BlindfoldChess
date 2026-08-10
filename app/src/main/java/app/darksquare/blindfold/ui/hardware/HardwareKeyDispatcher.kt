package app.darksquare.blindfold.ui.hardware

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Classifies raw Android KeyEvents on the volume rockers into high-level
 * gestures: Single, Double, LongPress. Long-press detection uses a coroutine
 * timer scheduled on KEY_DOWN; the timer is cancelled if KEY_UP arrives first.
 *
 * Connect [dispatch] from Activity.dispatchKeyEvent and consume [events]
 * from the ViewModel.
 */
class HardwareKeyDispatcher(
    private val scope: CoroutineScope,
    private val longPressMs: Long = 500,
    private val doubleClickMs: Long = 280
) {
    enum class Button { VolumeUp, VolumeDown }
    sealed interface Gesture {
        val button: Button
        data class Single(override val button: Button) : Gesture
        data class Double(override val button: Button) : Gesture
        data class LongPress(override val button: Button) : Gesture
    }

    private val out = Channel<Gesture>(Channel.BUFFERED)
    val events: Flow<Gesture> = out.receiveAsFlow()

    private data class PerButtonState(
        var downAt: Long = 0,
        var longPressJob: Job? = null,
        var longPressFired: Boolean = false,
        var pendingSingleJob: Job? = null,
        var lastUpAt: Long = 0
    )
    private val state = mutableMapOf<Button, PerButtonState>()

    /**
     * Returns true if the event was consumed by the dispatcher and the
     * system default behaviour (volume change) should be suppressed.
     */
    fun dispatch(event: KeyEvent): Boolean {
        val btn = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Button.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> Button.VolumeDown
            else -> return false
        }
        val s = state.getOrPut(btn) { PerButtonState() }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) onDown(btn, s)
                true   // swallow so OS doesn't change volume
            }
            KeyEvent.ACTION_UP -> { onUp(btn, s); true }
            else -> true
        }
    }

    private fun onDown(btn: Button, s: PerButtonState) {
        s.downAt = SystemClock.uptimeMillis()
        s.longPressFired = false
        s.longPressJob?.cancel()
        s.longPressJob = scope.launch {
            delay(longPressMs)
            s.longPressFired = true
            s.pendingSingleJob?.cancel()    // long-press overrides any pending single
            out.trySend(Gesture.LongPress(btn))
        }
    }

    private fun onUp(btn: Button, s: PerButtonState) {
        s.longPressJob?.cancel()
        if (s.longPressFired) return        // already dispatched

        val now = SystemClock.uptimeMillis()
        val sinceLastUp = now - s.lastUpAt
        s.lastUpAt = now

        if (s.pendingSingleJob?.isActive == true && sinceLastUp <= doubleClickMs) {
            // second click within window -> Double
            s.pendingSingleJob?.cancel()
            s.pendingSingleJob = null
            out.trySend(Gesture.Double(btn))
        } else {
            // schedule a Single, may be promoted to Double if another click arrives
            s.pendingSingleJob = scope.launch {
                delay(doubleClickMs)
                out.trySend(Gesture.Single(btn))
            }
        }
    }
}
