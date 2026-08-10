package app.darksquare.blindfold.domain.repo

import app.darksquare.blindfold.domain.model.ChessMove

sealed interface LichessEvent {
    data class GameStart(val id: String, val opponent: String, val whiteToMove: Boolean) : LichessEvent
    data class GameFinish(val id: String, val winner: String?) : LichessEvent
    data class Challenge(val id: String, val from: String) : LichessEvent
}

data class LichessGameState(
    val gameId: String,
    val fen: String,
    val movesUci: List<String>,
    val whiteMs: Long,
    val blackMs: Long,
    val status: String,          // "started", "mate", "resign", ...
    val lastMove: ChessMove?
)

data class LichessPuzzle(
    val id: String,
    val fen: String,
    val solutionUci: List<String>,
    val rating: Int,
    val themes: List<String>
)
