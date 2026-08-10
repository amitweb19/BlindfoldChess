package app.darksquare.blindfold.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import app.darksquare.blindfold.data.UserPhonemeStore
import app.darksquare.blindfold.domain.repo.LichessRepository
import app.darksquare.blindfold.ui.calibration.CalibrationScreen
import app.darksquare.blindfold.ui.calibration.CalibrationViewModel
import app.darksquare.blindfold.ui.game.GameEffect
import app.darksquare.blindfold.ui.game.GameViewModel
import app.darksquare.blindfold.ui.hardware.HardwareKeyDispatcher
import app.darksquare.blindfold.ui.haptics.HapticEngine
import app.darksquare.blindfold.domain.repo.TtsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only Activity in the app. Owns the [HardwareKeyDispatcher] so that
 * volume rocker events can be intercepted *before* the system handles them.
 *
 * Note: `dispatchKeyEvent` runs on the main thread, fires earliest in the
 * key event pipeline, and lets us return `true` to swallow the OS default
 * (volume change). Long-press + multi-tap classification is offloaded to
 * the dispatcher's coroutine scope so we don't block the UI thread.
 */
@AndroidEntryPoint
class BlindfoldActivity : ComponentActivity() {

    private val vm: GameViewModel by viewModels()
    private val calibVm: CalibrationViewModel by viewModels()
    @Inject lateinit var haptics: HapticEngine
    @Inject lateinit var tts: TtsRepository
    @Inject lateinit var store: UserPhonemeStore
    @Inject lateinit var lichess: LichessRepository

    private lateinit var keys: HardwareKeyDispatcher

    private var micGranted  by mutableStateOf(false)
    private var calibrated  by mutableStateOf(false)
    private var lichessAuthed by mutableStateOf(false)
    private var skipLichessLogin by mutableStateOf(false)
    private var loginError by mutableStateOf<String?>(null)

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        keys = HardwareKeyDispatcher(scope = lifecycleScope)

        calibrated = store.isCalibrated()

        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO)

        // Hardware gestures -> ViewModel intents
        lifecycleScope.launch { keys.events.collect(vm::onHardware) }

        // Lichess auth state -> routing gate
        lifecycleScope.launch {
            lichess.isAuthenticated().collect { authed ->
                lichessAuthed = authed
            }
        }

        // One-shot effects -> system services (TTS + Vibrator)
        lifecycleScope.launch {
            vm.effects.collect { fx ->
                when (fx) {
                    is GameEffect.Speak    -> tts.speak(fx.text, interrupt = true)
                    is GameEffect.Vibrate  -> haptics.play(fx.pattern)
                    is GameEffect.SnackBar -> Unit /* surfaced by Compose */
                }
            }
        }

        setContent {
            if (!micGranted) {
                PermissionScreen { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
            } else if (!lichessAuthed && !skipLichessLogin) {
                LichessLoginScreen(
                    onLogin = { token ->
                        lifecycleScope.launch {
                            val ok = lichess.authenticateWithToken(token)
                            loginError = if (ok) null else "Invalid token"
                        }
                    },
                    onSkip = {
                        skipLichessLogin = true
                        loginError = null
                    },
                    errorText = loginError
                )
            } else if (!calibrated) {
                CalibrationScreen(vm = calibVm, onDone = { calibrated = true })
            } else {
                BlindfoldApp(vm)
            }
        }
    }

    /**
     * Intercept volume keys here so they never reach AudioManager.
     * Returns true (consumed) for VOLUME_UP/DOWN; everything else falls
     * through to the default dispatcher.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keys.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
