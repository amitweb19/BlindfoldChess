package app.darksquare.blindfold.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticEngine @Inject constructor(@ApplicationContext ctx: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    enum class Pattern {
        ListeningStart,    // short rising tick
        ListeningStop,     // single soft pulse
        MoveAccepted,      // crisp double-tap
        MoveRejected,      // long buzz
        ConfirmationNeeded // triple light pulse
    }

    fun play(pattern: Pattern) {
        if (!vibrator.hasVibrator()) return
        val effect = when (pattern) {
            Pattern.ListeningStart    -> VibrationEffect.createOneShot(40, 180)
            Pattern.ListeningStop     -> VibrationEffect.createOneShot(25, 80)
            Pattern.MoveAccepted      -> VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1)
            Pattern.MoveRejected      -> VibrationEffect.createOneShot(220, 255)
            Pattern.ConfirmationNeeded-> VibrationEffect.createWaveform(longArrayOf(0, 25, 90, 25, 90, 25), -1)
        }
        vibrator.vibrate(effect)
    }
}
