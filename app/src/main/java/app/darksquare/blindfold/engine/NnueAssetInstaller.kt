package app.darksquare.blindfold.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NnueAssetInstaller @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    data class Paths(val big: String, val small: String)

    suspend fun ensureInstalled(): Paths = withContext(Dispatchers.IO) {
        Paths(
            big = copyIfMissing(BIG_NET),
            small = copyIfMissing(SMALL_NET)
        )
    }

    private fun copyIfMissing(name: String): String {
        val out = File(ctx.filesDir, name)
        if (!out.exists() || out.length() == 0L) {
            ctx.assets.open(name).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out.absolutePath
    }

    companion object {
        const val BIG_NET = "nn-c288c895ea92.nnue"
        const val SMALL_NET = "nn-37f18f62d772.nnue"
    }
}
