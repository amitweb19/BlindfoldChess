package app.darksquare.blindfold.engine

internal object SfishNative {
    init { System.loadLibrary("stockfish") }

    external fun nativeStart(): Long
    external fun nativeStop(handle: Long)
    external fun nativeSend(handle: Long, command: String)

    private var lineListener: ((String) -> Unit)? = null

    fun setLineListener(listener: (String) -> Unit) { lineListener = listener }

    @JvmStatic
    private fun onEngineLine(line: String) { lineListener?.invoke(line) }
}
