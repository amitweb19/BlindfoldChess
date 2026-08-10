package app.darksquare.blindfold.ui.game

import app.darksquare.blindfold.domain.model.ChessEngineEvaluation
import app.darksquare.blindfold.domain.model.ChessMove
import app.darksquare.blindfold.domain.model.ClockState

/** UI state for the Blindfold/Board screen (MVI). */
data class GameUiState(
    val mode: Mode = Mode.Stockfish,
    val engineElo: Int = 1800,
    val boardTheme: BoardTheme = BoardTheme.Classic,
    val pieceTheme: PieceTheme = PieceTheme.Classic,
    val onlineGameId: String? = null,
    val onlineSearching: Boolean = false,
    val onlineStatus: String? = null,
    val playerIsWhite: Boolean = true,
    val blindfoldStrict: Boolean = true,           // visual board hidden
    val fen: String = STARTPOS,
    val moves: List<ChessMove> = emptyList(),
    val whiteToMove: Boolean = true,
    val clock: ClockState? = null,
    val eval: ChessEngineEvaluation? = null,
    val stage: Stage = Stage.Idle,
    val pendingMoveSan: String? = null,            // staged voice move awaiting confirmation
    val pendingMoveUci: String? = null,
    val lastEngineMoveSan: String? = null,
    val lastError: String? = null,
    val rawFailedSpeech: String? = null,   // non-null → show manual-input dialog
    val listening: Boolean = false,
    val authenticated: Boolean = false,
) {
    enum class Mode { Stockfish, Lichess, Puzzle }
    enum class Stage { Idle, Listening, Staged, Transmitting }
    enum class BoardTheme { Classic, Brown, Blue, Forest }
    enum class PieceTheme { Classic, Solid, Neo, Outline }

    companion object { const val STARTPOS = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" }
}

/** Intents = all the things that can change state. */
sealed interface GameIntent {
    data object ToggleBlindfold : GameIntent
    data object ReadLastMove : GameIntent
    data object ReadEvaluation : GameIntent
    data object ReadClock : GameIntent
    data object StartListening : GameIntent
    data object StopListening : GameIntent
    data class HeardSpeech(val candidates: List<String>) : GameIntent
    data object ConfirmStagedMove : GameIntent
    data object CancelStagedMove : GameIntent
    data class SubmitMoveUci(val uci: String) : GameIntent      // engine/lichess delivered move
    data class EngineEval(val eval: ChessEngineEvaluation) : GameIntent
    data class Error(val message: String) : GameIntent
    data class SetMode(val mode: GameUiState.Mode) : GameIntent
    data class SetEngineElo(val elo: Int) : GameIntent
    data class SetBoardTheme(val theme: GameUiState.BoardTheme) : GameIntent
    data class SetPieceTheme(val theme: GameUiState.PieceTheme) : GameIntent
    data object StartOnlineMatch : GameIntent
    data object LeaveOnlineGame : GameIntent
    /** User manually selected piece+square after a recognition failure. */
    data class ManualMove(val piece: String, val file: Char, val rank: Int) : GameIntent
    data class ManualMoveText(val moveText: String) : GameIntent
    data object DismissManualInput : GameIntent
}

/** Side-effects that aren't state. */
sealed interface GameEffect {
    data class Speak(val text: String) : GameEffect
    data class Vibrate(val pattern: app.darksquare.blindfold.ui.haptics.HapticEngine.Pattern) : GameEffect
    data class SnackBar(val text: String) : GameEffect
}
