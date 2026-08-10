package app.darksquare.blindfold.domain.parser

import app.darksquare.blindfold.data.UserPhonemeStore
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import org.apache.commons.text.similarity.LevenshteinDistance

/**
 * Converts a free-form spoken phrase into a SAN string that is legal in [board].
 *
 * Strategy:
 *   1. Normalise text (lowercase, expand digits, fix common STT homophones).
 *   2. Detect special tokens (castling, promotion).
 *   3. Extract piece + destination square + optional disambiguator/capture.
 *   4. Enumerate legal moves; score each candidate against the parsed intent.
 *   5. Return the highest-scoring SAN, or null if no candidate scores above threshold.
 */
class VoiceMoveParser(private val store: UserPhonemeStore) {

    fun parse(rawSpeech: String, fen: String): ParseResult {
        val board = Board().apply { loadFromFen(fen) }
        val legal = MoveGenerator.generateLegalMoves(board)
        val text = normalise(rawSpeech)

        // --- Castling shortcuts -----------------------------------------
        if (text.matchesAny(CASTLE_KS)) return pickCastle(board, legal, kingside = true)
        if (text.matchesAny(CASTLE_QS)) return pickCastle(board, legal, kingside = false)

        // --- Resign / draw offers (caller decides what to do) -----------
        if (text.matchesAny(RESIGN_PHRASES)) return ParseResult.Command(CommandIntent.Resign)
        if (text.matchesAny(DRAW_PHRASES))   return ParseResult.Command(CommandIntent.OfferDraw)

        // --- Tokenise piece / square / capture / promotion --------------
        val piece = detectPiece(text) // null = any piece; non-null = filter to that type
        val isCapture = CAPTURE_WORDS.any { it in text }
        val promotion = detectPromotion(text)
        val toSquare = detectSquare(text, preferLast = true)
            ?: return ParseResult.Failure("Could not hear a destination square")
        val fromHint = detectSquare(text.substringBefore(toSquare.toString().lowercase()), preferLast = true)
        val fromFileHint = detectFile(text)

        // Promotion is always a pawn move — don't filter by the promotion piece type
        val effectivePiece = if (promotion != null) piece("PAWN") else piece

        val candidates = legal.filter { mv ->
            mv.to == toSquare &&
                (effectivePiece == null ||
                    board.getPiece(mv.from).pieceType.name.equals(pieceLetter(effectivePiece), ignoreCase = true))
        }

        if (candidates.isEmpty()) return ParseResult.Failure("No legal move to ${toSquare.value()}")

        val scored = candidates.map { mv -> mv to score(mv, board, isCapture, fromHint, fromFileHint, promotion, piece != null) }
        val best = scored.maxByOrNull { it.second }!!
        return if (best.second >= MIN_SCORE) {
            val san = moveToSan(board, best.first)
            ParseResult.Move(san, best.first.toString())
        } else ParseResult.Failure("Move ambiguous or low confidence")
    }

    // --- Normalisation -------------------------------------------------------
    private fun normalise(s: String): String {
        var t = s.lowercase().trim()
        // Apply user mappings. We honor any mapping whose value is a canonical
        // "(piece) (square)" phrase or a single chess token (piece/file/rank/square),
        // because the user explicitly trained those.
        store.getMappings().forEach { (k, v) ->
            val key = k.lowercase().trim()
            val value = v.lowercase().trim()
            if (key.isBlank() || value.isBlank()) return@forEach
            if (!isSafeMappingValue(value)) return@forEach
            t = Regex("\\b${Regex.escape(key)}\\b").replace(t, value)
        }
        // Common STT homophones — word-boundary regex prevents "knight" becoming "kknight"
        HOMOPHONES.forEach { (k, v) -> t = Regex("\\b${Regex.escape(k)}\\b").replace(t, v) }
        // Strip navigation/filler words that carry no chess meaning
        t = t.split(" ").filter { it.isNotEmpty() && it !in NOISE_WORDS }.joinToString(" ")
        // Spoken words → canonical chess tokens (files, digits, piece names)
        WORD_TOKENS.forEach { (k, v) -> t = Regex("\\b$k\\b").replace(t, v) }
        // collapse spaces
        return t.replace(Regex("\\s+"), " ")
    }

    private fun String.matchesAny(patterns: List<Regex>) = patterns.any { it.containsMatchIn(this) }

    private fun isSafeMappingValue(value: String): Boolean {
        if (value.matches(Regex("^(pawn|knight|bishop|rook|queen|king)$"))) return true
        if (value.matches(Regex("^[a-h]$"))) return true
        if (value.matches(Regex("^[1-8]$"))) return true
        if (value.matches(Regex("^[a-h][1-8]$"))) return true
        if (value.matches(Regex("^(pawn|knight|bishop|rook|queen|king) [a-h][1-8]$"))) return true
        return false
    }

    private fun detectPiece(text: String): Piece? = when {
        " knight" in " $text" || "horse" in text -> piece("KNIGHT")
        " bishop" in " $text"                    -> piece("BISHOP")
        " rook" in " $text" || "castle" in text && "side" !in text -> piece("ROOK")
        " queen" in " $text"                     -> piece("QUEEN")
        " king" in " $text"                      -> piece("KING")
        " pawn" in " $text"                      -> piece("PAWN")
        else -> null
    }

    private fun piece(type: String): Piece = Piece.NONE.also { /* placeholder */ }
        .let { Piece.values().first { p -> p.pieceType?.name == type } }

    private fun pieceLetter(p: Piece): String = p.pieceType?.name.orEmpty()

    private fun detectSquare(text: String, preferLast: Boolean): Square? {
        val matches = SQUARE_REGEX.findAll(text).map { it.value.replace(" ", "") }.toList()
        val raw = (if (preferLast) matches.lastOrNull() else matches.firstOrNull()) ?: return null
        return runCatching { Square.fromValue(raw.uppercase()) }.getOrNull()
    }

    private fun detectFile(text: String): Char? =
        FILE_REGEX.find(text)?.value?.firstOrNull()?.lowercaseChar()

    private fun detectPromotion(text: String): Piece? = when {
        "promote" in text || "equals" in text || "=" in text -> when {
            "queen" in text -> piece("QUEEN")
            "rook" in text -> piece("ROOK")
            "bishop" in text -> piece("BISHOP")
            "knight" in text -> piece("KNIGHT")
            else -> piece("QUEEN")
        }
        else -> null
    }

    private fun score(
        mv: Move, board: Board, captureSpoken: Boolean,
        fromHint: Square?, fileHint: Char?, promotion: Piece?, pieceExplicit: Boolean
    ): Int {
        var s = 50
        val actuallyCaptures = board.getPiece(mv.to) != Piece.NONE
        if (captureSpoken == actuallyCaptures) s += 25 else s -= 15
        if (pieceExplicit) s += 30          // user named the piece: strong positive signal
        if (fromHint != null && fromHint == mv.from) s += 30
        if (fileHint != null && mv.from.file.name.last().equals(fileHint, ignoreCase = true)) s += 10
        if (promotion != null && mv.promotion.pieceType == promotion.pieceType) s += 20
        return s
    }

    private fun pickCastle(board: Board, legal: List<Move>, kingside: Boolean): ParseResult {
        val king = if (board.sideToMove == Side.WHITE) Square.E1 else Square.E8
        val target = if (kingside) (if (board.sideToMove == Side.WHITE) Square.G1 else Square.G8)
                     else          (if (board.sideToMove == Side.WHITE) Square.C1 else Square.C8)
        val mv = legal.firstOrNull { it.from == king && it.to == target }
            ?: return ParseResult.Failure("Cannot castle ${if (kingside) "kingside" else "queenside"}")
        return ParseResult.Move(if (kingside) "O-O" else "O-O-O", mv.toString())
    }

    // --- Fuzzy fallback ------------------------------------------------------
    /** Used when the parser fails — pick the closest known phrase from candidates. */
    fun fuzzyCorrect(input: String, vocabulary: List<String>): String? {
        val lev = LevenshteinDistance(3)
        return vocabulary.minByOrNull { lev.apply(input.lowercase(), it.lowercase()) ?: Int.MAX_VALUE }
    }

    companion object {
        private const val MIN_SCORE = 60

        private val SQUARE_REGEX = Regex("\\b([a-h])\\s*([1-8])\\b")
        private val FILE_REGEX   = Regex("\\b[a-h]\\b")

        private val CASTLE_KS = listOf(
            Regex("castle\\s*(king\\s*side|short)"),
            Regex("short\\s*castle"),
            Regex("king\\s*side\\s*castle"),
            Regex("\\bo[-\\s]o\\b(?![-\\s]o)"),
            Regex("\\boh\\s+oh\\b(?!\\s+oh)"),
            Regex("zero\\s+zero\\b(?!\\s+zero)")
        )
        private val CASTLE_QS = listOf(
            Regex("castle\\s*(queen\\s*side|long)"),
            Regex("long\\s*castle"),
            Regex("queen\\s*side\\s*castle"),
            Regex("\\bo[-\\s]o[-\\s]o\\b"),
            Regex("\\boh\\s+oh\\s+oh\\b"),
            Regex("zero\\s+zero\\s+zero")
        )
        private val CAPTURE_WORDS = listOf("takes", "captures", "x ", "by ")
        private val RESIGN_PHRASES = listOf(Regex("\\bresign"), Regex("\\bgive up"))
        private val DRAW_PHRASES   = listOf(Regex("offer draw"), Regex("\\bpropose draw"))

        /** Filler words with no chess meaning — stripped before parsing. */
        private val NOISE_WORDS = setOf("to", "on", "at", "from", "the", "go", "play", "move")

        /** Common Android STT homophones for chess vocabulary. */
        private val HOMOPHONES = mapOf(
            "night" to "knight", "nite" to "knight", "knife" to "knight",
            "be shop" to "bishop", "bee shop" to "bishop",
            "rock" to "rook", "root" to "rook",
            "pun" to "pawn", "porn" to "pawn", "prawn" to "pawn",
            "ate" to "8",   // /eɪt/ = same sound as "eight"
            "tree" to "3"   // accent variation of "three"
        )
        /** Spoken words mapped to canonical chess tokens using word-boundary regex. */
        private val WORD_TOKENS = mapOf(
            // Digit words
            "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
            // File-letter pronunciations that STT commonly returns
            "ef" to "f", "gee" to "g",
            "aitch" to "h", "eitch" to "h", "haitch" to "h",
            "ay" to "a", "ee" to "e",
            "bee" to "b",
            "see" to "c", "sea" to "c",
            "dee" to "d"
        )
    }
}

sealed interface ParseResult {
    data class Move(val san: String, val uci: String) : ParseResult
    data class Command(val intent: CommandIntent) : ParseResult
    data class Failure(val reason: String) : ParseResult
}

enum class CommandIntent { Resign, OfferDraw }

/**
 * Computes Standard Algebraic Notation for [mv] given the position in [board] before the move.
 * Works without relying on MoveBackup internals, which vary across chesslib versions.
 */
internal fun moveToSan(board: Board, mv: Move): String {
    val pt = board.getPiece(mv.from).pieceType

    // Castling: king moves two files horizontally
    if (pt == PieceType.KING) {
        val df = mv.to.file.ordinal - mv.from.file.ordinal
        if (df == 2)  return "O-O"
        if (df == -2) return "O-O-O"
    }

    val isCapture = board.getPiece(mv.to) != Piece.NONE ||
        (pt == PieceType.PAWN && mv.from.file != mv.to.file) // en passant

    val sb = StringBuilder()

    // Piece letter (omitted for pawns)
    if (pt != PieceType.PAWN) {
        sb.append(when (pt) {
            PieceType.KNIGHT -> 'N'
            PieceType.BISHOP -> 'B'
            PieceType.ROOK   -> 'R'
            PieceType.QUEEN  -> 'Q'
            else             -> 'K'
        })
    }

    // Disambiguation for non-pawn pieces
    if (pt != PieceType.PAWN) {
        val ambig = MoveGenerator.generateLegalMoves(board).filter { o ->
            o != mv && o.to == mv.to && board.getPiece(o.from).pieceType == pt
        }
        if (ambig.isNotEmpty()) {
            val sameFile = ambig.any { it.from.file == mv.from.file }
            val sameRank = ambig.any { it.from.rank == mv.from.rank }
            // FIDE rules: use file if that resolves ambiguity, rank if same file, both if needed
            if (sameRank || !sameFile) sb.append(mv.from.file.name.last().lowercaseChar())
            if (sameFile)              sb.append(mv.from.rank.name.last())
        }
    }

    // Capture indicator
    if (isCapture) {
        if (pt == PieceType.PAWN) sb.append(mv.from.file.name.last().lowercaseChar())
        sb.append('x')
    }

    // Destination square (e.g. "e4")
    sb.append(mv.to.value().lowercase())

    // Promotion
    if (mv.promotion != Piece.NONE) {
        sb.append('=').append(when (mv.promotion.pieceType) {
            PieceType.KNIGHT -> 'N'
            PieceType.BISHOP -> 'B'
            PieceType.ROOK   -> 'R'
            else             -> 'Q'
        })
    }

    // Check / checkmate: apply move on clone, test if opponent's king is attacked
    val after = board.clone()
    after.doMove(mv)
    if (after.isKingAttacked) {
        sb.append(if (MoveGenerator.generateLegalMoves(after).isEmpty()) '#' else '+')
    }

    return sb.toString()
}
