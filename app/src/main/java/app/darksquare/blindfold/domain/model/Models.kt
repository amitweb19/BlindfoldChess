package app.darksquare.blindfold.domain.model

/** Side-to-move neutral move representation. */
data class ChessMove(
    val san: String,        // e.g. "Nxf3", "O-O", "e8=Q+"
    val uci: String,        // e.g. "g1f3"
    val fromFen: String,    // position before the move
    val toFen: String       // position after the move
)

data class ChessEngineEvaluation(
    val cp: Int?,           // centipawns from side-to-move perspective
    val mateIn: Int?,       // signed plies-to-mate, null if not mating
    val depth: Int,
    val bestMoveUci: String?
)

sealed interface ChessEngineLevel {
    data class Elo(val rating: Int) : ChessEngineLevel       // UCI_LimitStrength + UCI_Elo
    data class Depth(val plies: Int) : ChessEngineLevel      // fixed search depth
    data class MoveTime(val ms: Long) : ChessEngineLevel
}

data class ClockState(
    val whiteMs: Long,
    val blackMs: Long,
    val incrementMs: Long,
    val whiteToMove: Boolean
)

data class GameContext(
    val gameId: String?,          // Lichess game id, null for vs-engine
    val fen: String,
    val playerIsWhite: Boolean,
    val clock: ClockState?
)
