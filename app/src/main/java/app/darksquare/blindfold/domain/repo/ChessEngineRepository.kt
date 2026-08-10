package app.darksquare.blindfold.domain.repo

import app.darksquare.blindfold.domain.model.ChessEngineEvaluation
import app.darksquare.blindfold.domain.model.ChessEngineLevel
import kotlinx.coroutines.flow.Flow

/**
 * Thin abstraction over a UCI engine. Implementation wraps the JNI bridge
 * to libstockfish.so. Engine I/O is serialized on a single dispatcher.
 */
interface ChessEngineRepository {
    /** Starts the native engine process. Idempotent. */
    suspend fun start()

    /** Stops and frees native resources. */
    suspend fun stop()

    /** Configure strength via ELO, depth, or movetime. */
    suspend fun setLevel(level: ChessEngineLevel)

    /** Send a new position (FEN + optional move list in UCI). */
    suspend fun setPosition(fen: String, movesUci: List<String> = emptyList())

    /**
     * Search and return the best move (UCI). Cancellation sends `stop` to the engine.
     * Emits progressive evaluations until bestmove is produced.
     */
    fun search(level: ChessEngineLevel): Flow<ChessEngineEvaluation>

    /** Single-shot helper that returns only the final bestmove. */
    suspend fun bestMove(level: ChessEngineLevel): String
}
