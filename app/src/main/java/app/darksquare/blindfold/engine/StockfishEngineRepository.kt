package app.darksquare.blindfold.engine

import app.darksquare.blindfold.domain.model.ChessEngineEvaluation
import app.darksquare.blindfold.domain.model.ChessEngineLevel
import app.darksquare.blindfold.domain.repo.ChessEngineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class StockfishEngineRepository @Inject constructor(
    private val nnue: NnueAssetInstaller,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ChessEngineRepository {

    private val mutex = Mutex()
    private var handle: Long = 0L
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val forwarder = LineForwarder(lines)

    override suspend fun start() = withContext(io) {
        mutex.withLock {
            if (handle != 0L) return@withLock
            val paths = nnue.ensureInstalled()
            SfishNative.setLineListener(forwarder)
            handle = SfishNative.nativeStart()
            send("uci")
            awaitToken("uciok")
            send("setoption name EvalFile value ${paths.big}")
            send("setoption name EvalFileSmall value ${paths.small}")
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            send("isready")
            awaitToken("readyok")
        }
    }

    override suspend fun stop() = withContext(io) {
        mutex.withLock {
            if (handle == 0L) return@withLock
            send("quit")
            SfishNative.nativeStop(handle)
            handle = 0L
        }
    }

    override suspend fun setLevel(level: ChessEngineLevel) = withContext(io) {
        mutex.withLock {
            when (level) {
                is ChessEngineLevel.Elo -> {
                    send("setoption name UCI_LimitStrength value true")
                    send("setoption name UCI_Elo value ${level.rating.coerceIn(1320, 3190)}")
                }
                is ChessEngineLevel.Depth, is ChessEngineLevel.MoveTime -> {
                    send("setoption name UCI_LimitStrength value false")
                }
            }
            send("isready"); awaitToken("readyok")
        }
    }

    override suspend fun setPosition(fen: String, movesUci: List<String>) = withContext(io) {
        mutex.withLock {
            val moves = if (movesUci.isEmpty()) "" else " moves " + movesUci.joinToString(" ")
            send("position fen $fen$moves")
        }
    }

    override fun search(level: ChessEngineLevel): Flow<ChessEngineEvaluation> = callbackFlow {
        var gotBestMove = false
        mutex.withLock {
            val goCmd = when (level) {
                is ChessEngineLevel.Depth -> "go depth ${level.plies}"
                is ChessEngineLevel.MoveTime -> "go movetime ${level.ms}"
                is ChessEngineLevel.Elo -> "go movetime 1500"
            }
            // Drain stale output (e.g. stop-response bestmove from a previous search)
            while (lines.tryReceive().isSuccess) { }
            send(goCmd)

            var depth = 0
            var cp: Int? = null
            var mate: Int? = null
            while (!isClosedForSend) {
                val line = lines.receive()
                when {
                    line.startsWith("info ") -> {
                        depth = getTokenAfter(line, "depth") ?: depth
                        getTokenAfter(line, "score cp")?.also { cp = it; mate = null }
                        getTokenAfter(line, "score mate")?.also { mate = it; cp = null }
                        val pv = line.substringAfter(" pv ", "").substringBefore(' ')
                        trySend(ChessEngineEvaluation(cp, mate, depth, pv.takeIf { it.isNotBlank() }))
                    }
                    line.startsWith("bestmove ") -> {
                        gotBestMove = true
                        val best = line.removePrefix("bestmove ").substringBefore(' ')
                        trySend(ChessEngineEvaluation(cp, mate, depth, best))
                        close()
                    }
                }
            }
        }
        // Only send "stop" if bestmove never arrived (i.e. search was cancelled).
        // Sending "stop" after naturally receiving bestmove would queue a spurious
        // bestmove response that poisons the next search.
        awaitClose { if (!gotBestMove) runCatching { send("stop") } }
    }

    override suspend fun bestMove(level: ChessEngineLevel): String {
        var last: ChessEngineEvaluation? = null
        search(level).collect { last = it }
        return last?.bestMoveUci ?: error("Engine returned no bestmove")
    }

    private fun send(cmd: String) { SfishNative.nativeSend(handle, cmd) }
    private suspend fun awaitToken(token: String) {
        while (true) if (lines.receive().trim() == token) return
    }
    private fun getTokenAfter(line: String, key: String): Int? {
        val idx = line.indexOf("$key ").takeIf { it >= 0 } ?: return null
        return line.substring(idx + key.length + 1).substringBefore(' ').trim().toIntOrNull()
    }

    private class LineForwarder(private val lines: Channel<String>) : (String) -> Unit {
        override fun invoke(line: String) {
            lines.trySend(line)
        }
    }
}
