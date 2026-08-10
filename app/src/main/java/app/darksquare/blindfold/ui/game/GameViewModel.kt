package app.darksquare.blindfold.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.darksquare.blindfold.data.MappingHistoryEntry
import app.darksquare.blindfold.data.tts.SanToSpeech
import app.darksquare.blindfold.domain.model.ChessEngineLevel
import app.darksquare.blindfold.domain.parser.ParseResult
import app.darksquare.blindfold.data.UserPhonemeStore
import app.darksquare.blindfold.domain.model.ClockState
import app.darksquare.blindfold.domain.model.ChessEngineEvaluation
import app.darksquare.blindfold.domain.parser.VoiceMoveParser
import app.darksquare.blindfold.domain.parser.moveToSan
import app.darksquare.blindfold.domain.repo.ChessEngineRepository
import app.darksquare.blindfold.domain.repo.LichessEvent
import app.darksquare.blindfold.domain.repo.LichessRepository
import app.darksquare.blindfold.domain.repo.SttEvent
import app.darksquare.blindfold.domain.repo.SttRepository
import app.darksquare.blindfold.domain.repo.TtsRepository
import app.darksquare.blindfold.ui.hardware.HardwareKeyDispatcher
import app.darksquare.blindfold.ui.haptics.HapticEngine
import app.darksquare.blindfold.domain.model.ChessMove
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val engine: ChessEngineRepository,
    private val lichess: LichessRepository,
    private val stt: SttRepository,
    private val tts: TtsRepository,
    private val parser: VoiceMoveParser,
    private val store: UserPhonemeStore,
) : ViewModel() {

    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private val _effects = Channel<GameEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var listenJob: Job? = null
    private var eventsJob: Job? = null
    private var gameStateJob: Job? = null

    init {
        viewModelScope.launch {
            engine.start()
            engine.setLevel(ChessEngineLevel.Elo(_state.value.engineElo))
            tts.warmUp()
        }
        viewModelScope.launch {
            lichess.isAuthenticated().collect { authed ->
                reduce { copy(authenticated = authed) }
            }
        }
    }

    fun onIntent(intent: GameIntent) {
        when (intent) {
            GameIntent.ToggleBlindfold -> reduce { copy(blindfoldStrict = !blindfoldStrict) }

            GameIntent.ReadLastMove -> {
                val san = _state.value.moves.lastOrNull()?.san
                effect(GameEffect.Speak(san?.let(SanToSpeech::toEnglish) ?: "No moves yet"))
            }
            GameIntent.ReadEvaluation -> {
                val ev = _state.value.eval
                effect(GameEffect.Speak(SanToSpeech.evalToEnglish(ev?.cp, ev?.mateIn)))
            }
            GameIntent.ReadClock -> {
                val c = _state.value.clock
                if (c == null) effect(GameEffect.Speak("No clock"))
                else effect(GameEffect.Speak(
                    "White ${formatMs(c.whiteMs)}, black ${formatMs(c.blackMs)}"
                ))
            }

            GameIntent.StartListening -> startListening()
            GameIntent.StopListening  -> stopListening()
            is GameIntent.HeardSpeech -> handleSpeech(intent.candidates)

            GameIntent.ConfirmStagedMove -> confirmStaged()
            GameIntent.CancelStagedMove -> {
                reduce { copy(stage = GameUiState.Stage.Idle, pendingMoveSan = null) }
                effect(GameEffect.Speak("Move cancelled"))
            }

            is GameIntent.SubmitMoveUci -> { /* applied by engine/lichess collectors */ }
            is GameIntent.EngineEval    -> {
                val sign = if (_state.value.whiteToMove) 1 else -1
                val normalized = intent.eval.copy(
                    cp = intent.eval.cp?.times(sign),
                    mateIn = intent.eval.mateIn?.times(sign)
                )
                reduce { copy(eval = normalized) }
            }
            is GameIntent.Error         -> {
                reduce { copy(lastError = intent.message) }
                effect(GameEffect.Vibrate(HapticEngine.Pattern.MoveRejected))
                effect(GameEffect.Speak(intent.message))
            }
            is GameIntent.SetMode       -> reduce { copy(mode = intent.mode) }
            is GameIntent.SetEngineElo  -> {
                reduce { copy(engineElo = intent.elo) }
                viewModelScope.launch { engine.setLevel(ChessEngineLevel.Elo(intent.elo)) }
            }
            is GameIntent.SetBoardTheme -> reduce { copy(boardTheme = intent.theme) }
            is GameIntent.SetPieceTheme -> reduce { copy(pieceTheme = intent.theme) }
            GameIntent.StartOnlineMatch -> startOnlineMatchmaking()
            GameIntent.LeaveOnlineGame -> leaveOnlineGame()
            is GameIntent.ManualMove    -> handleManualMove(intent)
            is GameIntent.ManualMoveText -> handleManualMoveText(intent.moveText)
            GameIntent.DismissManualInput -> reduce { copy(rawFailedSpeech = null, stage = GameUiState.Stage.Idle) }
        }
    }

    fun getMappingsSnapshot(): Map<String, String> = store.getMappings()

    fun getMappingHistorySnapshot(limit: Int = 150): List<MappingHistoryEntry> = store.getHistory(limit)

    fun upsertMapping(spoken: String, mapped: String) {
        val key = spoken.lowercase().trim().replace(Regex("\\s+"), " ")
        val value = mapped.lowercase().trim().replace(Regex("\\s+"), " ")
        if (key.isBlank() || value.isBlank()) return
        val all = store.getMappings().toMutableMap()
        all[key] = value
        store.saveMappings(all)
        store.appendHistory(
            spoken = key,
            mapped = value,
            category = inferCategory(value),
            source = "manual-editor"
        )
    }

    /** Translate hardware gestures into intents. */
    fun onHardware(g: HardwareKeyDispatcher.Gesture) {
        val Up = HardwareKeyDispatcher.Button.VolumeUp
        val Dn = HardwareKeyDispatcher.Button.VolumeDown
        when (g) {
            is HardwareKeyDispatcher.Gesture.Single ->
                if (g.button == Up) onIntent(GameIntent.ReadLastMove)
                else                onIntent(if (_state.value.stage == GameUiState.Stage.Staged)
                                              GameIntent.ConfirmStagedMove else GameIntent.ReadClock)
            is HardwareKeyDispatcher.Gesture.Double ->
                if (g.button == Up) onIntent(GameIntent.ReadEvaluation)
                else                onIntent(GameIntent.CancelStagedMove)
            is HardwareKeyDispatcher.Gesture.LongPress ->
                if (g.button == Dn) onIntent(GameIntent.StartListening)
                else                onIntent(GameIntent.ToggleBlindfold)
        }
    }

    // ---- listening ---------------------------------------------------------
    private fun startListening() {
        if (listenJob?.isActive == true) return
        reduce { copy(listening = true, stage = GameUiState.Stage.Listening) }
        effect(GameEffect.Vibrate(HapticEngine.Pattern.ListeningStart))
        listenJob = viewModelScope.launch {
            stt.listen().collect { ev ->
                when (ev) {
                    is SttEvent.Final -> {
                        reduce { copy(listening = false) }
                        effect(GameEffect.Vibrate(HapticEngine.Pattern.ListeningStop))
                        onIntent(GameIntent.HeardSpeech(ev.candidates))
                    }
                    is SttEvent.Error -> {
                        reduce { copy(listening = false, stage = GameUiState.Stage.Idle) }
                        effect(GameEffect.Speak("I didn't catch that, please try again"))
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel(); listenJob = null
        reduce { copy(listening = false, stage = GameUiState.Stage.Idle) }
    }

    private fun handleSpeech(candidates: List<String>) {
        val fen = _state.value.fen
        val rawSpeech = candidates.firstOrNull() ?: ""
        for (candidate in candidates) {
            when (val r = parser.parse(candidate, fen)) {
                is ParseResult.Move    -> { stage(r.san, r.uci); return }
                is ParseResult.Command -> { /* TODO: resign/draw flows */ return }
                is ParseResult.Failure -> continue
            }
        }
        // All candidates failed — show manual-input dialog so the user can select the move
        reduce { copy(stage = GameUiState.Stage.Idle, rawFailedSpeech = rawSpeech) }
        effect(GameEffect.Speak("I didn't understand, please select the move"))
    }

    private fun stage(san: String, uci: String) {
        reduce { copy(stage = GameUiState.Stage.Staged, pendingMoveSan = san, pendingMoveUci = uci) }
        effect(GameEffect.Vibrate(HapticEngine.Pattern.ConfirmationNeeded))
        effect(GameEffect.Speak("Confirm ${SanToSpeech.toEnglish(san)}"))
    }

    private fun confirmStaged() {
        val san = _state.value.pendingMoveSan ?: return
        val uci = _state.value.pendingMoveUci ?: return
        val currentFen = _state.value.fen

        if (_state.value.mode == GameUiState.Mode.Lichess) {
            confirmStagedLichess(san, uci, currentFen)
            return
        }

        // In Stockfish mode the human always plays White — block moves while engine is thinking
        if (_state.value.mode == GameUiState.Mode.Stockfish && !_state.value.whiteToMove) {
            reduce { copy(stage = GameUiState.Stage.Idle, pendingMoveSan = null, pendingMoveUci = null) }
            effect(GameEffect.Speak("Please wait for the engine"))
            return
        }

        reduce { copy(stage = GameUiState.Stage.Transmitting, pendingMoveSan = null, pendingMoveUci = null) }

        viewModelScope.launch {
            val board = com.github.bhlangonijr.chesslib.Board()
            board.loadFromFen(currentFen)
            val move = com.github.bhlangonijr.chesslib.move.Move(uci, board.sideToMove)
            
            if (board.doMove(move)) {
                val newFen = board.fen
                val chessMove = ChessMove(
                    san = san,
                    uci = uci,
                    fromFen = currentFen,
                    toFen = newFen
                )

                reduce {
                    copy(
                        fen = newFen,
                        moves = moves + chessMove,
                        whiteToMove = board.sideToMove == com.github.bhlangonijr.chesslib.Side.WHITE,
                        eval = null,
                        stage = GameUiState.Stage.Idle
                    )
                }

                effect(GameEffect.Vibrate(HapticEngine.Pattern.MoveAccepted))
                effect(GameEffect.Speak(SanToSpeech.toEnglish(san)))

                // Trigger engine if in VsEngine mode
                if (_state.value.mode == GameUiState.Mode.Stockfish) {
                    try {
                        val allMovesUci = _state.value.moves.map { it.uci }
                        val engineBoard = board.clone()
                        val legalReplies = MoveGenerator.generateLegalMoves(engineBoard)
                        if (legalReplies.isEmpty()) {
                            effect(GameEffect.Speak("Game over"))
                            return@launch
                        }

                        engine.setPosition(GameUiState.STARTPOS, allMovesUci)
                        fun applyEngineUci(uci: String): Move? {
                            if (uci.isBlank() || uci == "(none)" || uci == "0000") return null
                            // Prefer direct UCI parsing; if that fails, fall back to legal-move lookup.
                            val parsedMove = runCatching { Move(uci, engineBoard.sideToMove) }.getOrNull()
                            if (parsedMove != null && engineBoard.doMove(parsedMove, true)) return parsedMove

                            val legalMoves = MoveGenerator.generateLegalMoves(engineBoard)
                            val fallback = legalMoves.firstOrNull { it.toString().equals(uci, ignoreCase = true) }
                                ?: return null
                            return if (engineBoard.doMove(fallback, true)) fallback else null
                        }

                        suspend fun requestEngineUci(): String? =
                            withTimeoutOrNull(5000) {
                                val targetDepth = EngineDifficulty.depthForRating(_state.value.engineElo)
                                var lastEval: ChessEngineEvaluation? = null
                                engine.search(ChessEngineLevel.Depth(targetDepth)).collect { ev ->
                                    lastEval = ev
                                    onIntent(GameIntent.EngineEval(ev))
                                }
                                lastEval?.bestMoveUci
                            }

                        suspend fun requestWithRecovery(): String? {
                            requestEngineUci()?.let { return it }
                            // Restart once if engine didn't answer in time.
                            runCatching {
                                engine.stop()
                                engine.start()
                                engine.setLevel(ChessEngineLevel.Elo(_state.value.engineElo))
                                engine.setPosition(GameUiState.STARTPOS, allMovesUci)
                            }
                            return requestEngineUci()
                        }

                        var engineUci = requestWithRecovery() ?: ""
                        if (engineUci == "(none)" || engineUci == "0000") {
                            effect(GameEffect.Speak("Game over"))
                            return@launch
                        }

                        var engineMove = applyEngineUci(engineUci)
                        if (engineMove == null) {
                            // One lightweight resync+retry for transient engine output mismatch.
                            engine.setPosition(GameUiState.STARTPOS, allMovesUci)
                            engineUci = requestWithRecovery() ?: ""
                            if (engineUci == "(none)" || engineUci == "0000") {
                                effect(GameEffect.Speak("Game over"))
                                return@launch
                            }
                            engineMove = applyEngineUci(engineUci)
                        }
                        if (engineMove == null) {
                            // Final safety fallback: keep game progressing instead of freezing.
                            val legalMoves = MoveGenerator.generateLegalMoves(engineBoard)
                            val fallback = legalMoves.firstOrNull() ?: run {
                                effect(GameEffect.Speak("Game over"))
                                return@launch
                            }
                            engineBoard.doMove(fallback, true)
                            engineMove = fallback
                            engineUci = fallback.toString()
                        }

                        val engineFen = engineBoard.fen
                        val engineSan = try { moveToSan(board, engineMove) } catch (_: Exception) { engineUci }

                        val engineChessMove = ChessMove(
                            san = engineSan,
                            uci = engineUci,
                            fromFen = newFen,
                            toFen = engineFen
                        )
                        reduce {
                            copy(
                                fen = engineFen,
                                moves = moves + engineChessMove,
                                whiteToMove = engineBoard.sideToMove == Side.WHITE
                            )
                        }
                        effect(GameEffect.Vibrate(HapticEngine.Pattern.MoveAccepted))
                        effect(GameEffect.Speak(SanToSpeech.toEnglish(engineSan)))
                    } catch (e: Exception) {
                        onIntent(GameIntent.Error("Engine error: ${e.message}"))
                    }
                }
            } else {
                onIntent(GameIntent.Error("Illegal move attempted"))
            }
        }
    }

    private fun confirmStagedLichess(san: String, uci: String, currentFen: String) {
        val gameId = _state.value.onlineGameId
        if (gameId.isNullOrBlank()) {
            reduce { copy(stage = GameUiState.Stage.Idle, pendingMoveSan = null, pendingMoveUci = null) }
            effect(GameEffect.Speak("Start an online match first"))
            return
        }
        val yourTurn = _state.value.playerIsWhite == _state.value.whiteToMove
        if (!yourTurn) {
            reduce { copy(stage = GameUiState.Stage.Idle, pendingMoveSan = null, pendingMoveUci = null) }
            effect(GameEffect.Speak("Please wait for opponent move"))
            return
        }

        reduce { copy(stage = GameUiState.Stage.Transmitting, pendingMoveSan = null, pendingMoveUci = null) }
        viewModelScope.launch {
            runCatching {
                // Local legality check before network call.
                val board = Board().apply { loadFromFen(currentFen) }
                val move = Move(uci, board.sideToMove)
                require(board.doMove(move)) { "Illegal move attempted" }
                lichess.makeMove(gameId, uci)
                effect(GameEffect.Vibrate(HapticEngine.Pattern.MoveAccepted))
                effect(GameEffect.Speak(SanToSpeech.toEnglish(san)))
                reduce { copy(stage = GameUiState.Stage.Idle) }
            }.onFailure {
                onIntent(GameIntent.Error("Lichess move failed: ${it.message}"))
                reduce { copy(stage = GameUiState.Stage.Idle) }
            }
        }
    }

    private fun startOnlineMatchmaking() {
        if (!_state.value.authenticated) {
            effect(GameEffect.Speak("Login to Lichess first"))
            return
        }
        if (eventsJob?.isActive == true || gameStateJob?.isActive == true) {
            effect(GameEffect.Speak("Already searching or in a game"))
            return
        }

        reduce { copy(onlineSearching = true, onlineStatus = "Searching for opponent...") }
        eventsJob = viewModelScope.launch {
            runCatching {
                lichess.seekGame(timeMinutes = 5, incrementSec = 0, rated = false)
                lichess.streamIncomingEvents().collect { ev ->
                    when (ev) {
                        is LichessEvent.GameStart -> {
                            reduce {
                                copy(
                                    onlineSearching = false,
                                    onlineGameId = ev.id,
                                    onlineStatus = "Connected vs ${ev.opponent}",
                                    playerIsWhite = ev.whiteToMove,
                                )
                            }
                            effect(GameEffect.Speak("Game started against ${ev.opponent}"))
                            startGameStateStream(ev.id)
                            eventsJob?.cancel()
                        }
                        is LichessEvent.GameFinish -> {
                            if (ev.id == _state.value.onlineGameId) {
                                reduce { copy(onlineStatus = "Game finished") }
                            }
                        }
                        is LichessEvent.Challenge -> Unit
                    }
                }
            }.onFailure {
                if (it is CancellationException) return@onFailure
                reduce { copy(onlineSearching = false, onlineStatus = null) }
                onIntent(GameIntent.Error("Lichess connection failed: ${it.message}"))
            }
        }
    }

    private fun startGameStateStream(gameId: String) {
        gameStateJob?.cancel()
        gameStateJob = viewModelScope.launch {
            runCatching {
                lichess.streamGameState(gameId).collect { gs ->
                    val snapshot = buildBoardSnapshot(gs.movesUci)
                    reduce {
                        copy(
                            onlineGameId = gameId,
                            fen = snapshot.fen,
                            moves = snapshot.moves,
                            whiteToMove = snapshot.whiteToMove,
                            clock = ClockState(gs.whiteMs, gs.blackMs, 0L, snapshot.whiteToMove),
                            onlineStatus = if (gs.status == "started") onlineStatus else "Game over: ${gs.status}",
                            stage = GameUiState.Stage.Idle,
                        )
                    }
                    if (gs.status != "started" && _state.value.onlineStatus?.startsWith("Game over") != true) {
                        effect(GameEffect.Speak("Game over"))
                    }
                }
            }.onFailure {
                if (it is CancellationException) return@onFailure
                onIntent(GameIntent.Error("Game stream failed: ${it.message}"))
            }
        }
    }

    private fun leaveOnlineGame() {
        eventsJob?.cancel(); eventsJob = null
        gameStateJob?.cancel(); gameStateJob = null
        reduce {
            copy(
                onlineSearching = false,
                onlineGameId = null,
                onlineStatus = null,
                stage = GameUiState.Stage.Idle,
            )
        }
    }

    private data class BoardSnapshot(
        val fen: String,
        val moves: List<ChessMove>,
        val whiteToMove: Boolean,
    )

    private fun buildBoardSnapshot(movesUci: List<String>): BoardSnapshot {
        val board = Board()
        val history = mutableListOf<ChessMove>()
        movesUci.forEach { uci ->
            val fromFen = board.fen
            val mv = Move(uci, board.sideToMove)
            if (board.doMove(mv)) {
                val san = runCatching {
                    val pre = Board().apply { loadFromFen(fromFen) }
                    moveToSan(pre, mv)
                }.getOrElse { uci }
                history += ChessMove(san = san, uci = uci, fromFen = fromFen, toFen = board.fen)
            }
        }
        return BoardSnapshot(
            fen = board.fen,
            moves = history,
            whiteToMove = board.sideToMove == Side.WHITE,
        )
    }

    // ---- manual fallback after recognition failure -----------------------
    private fun handleManualMove(intent: GameIntent.ManualMove) {
        val raw = _state.value.rawFailedSpeech ?: ""
        reduce { copy(rawFailedSpeech = null, stage = GameUiState.Stage.Idle) }
        viewModelScope.launch {
            val fen = _state.value.fen
            val board = Board().apply { loadFromFen(fen) }
            val legal = MoveGenerator.generateLegalMoves(board)
            val targetSq = runCatching {
                com.github.bhlangonijr.chesslib.Square.fromValue("${intent.file}${intent.rank}".uppercase())
            }.getOrNull() ?: run {
                effect(GameEffect.Speak("Invalid square")); return@launch
            }
            val pieceName = intent.piece.uppercase()
            val candidates = legal.filter { mv ->
                mv.to == targetSq &&
                    board.getPiece(mv.from).pieceType?.name == pieceName
            }
            when {
                candidates.isEmpty() -> effect(GameEffect.Speak("${intent.piece} to ${intent.file}${intent.rank} is not a legal move"))
                else -> {
                    val mv  = candidates.first()
                    val san = try { moveToSan(board, mv) } catch (_: Exception) { mv.toString() }
                    val canonical = canonicalMappingPhrase(board, mv)
                    if (raw.isNotBlank()) {
                        saveWordTraining(raw, canonical)
                    } else {
                        store.appendHistory(
                            spoken = san.lowercase(),
                            mapped = canonical,
                            category = "move_phrase",
                            source = "manual-direct"
                        )
                    }
                    stage(san, mv.toString())
                }
            }
        }
    }

    private fun handleManualMoveText(moveText: String) {
        val raw = _state.value.rawFailedSpeech ?: ""
        reduce { copy(rawFailedSpeech = null, stage = GameUiState.Stage.Idle) }
        viewModelScope.launch {
            val fen = _state.value.fen
            val board = Board().apply { loadFromFen(fen) }
            val legal = MoveGenerator.generateLegalMoves(board)
            val typed = moveText.trim()
            if (typed.isBlank()) {
                effect(GameEffect.Speak("Enter a move first"))
                return@launch
            }

            val matched = legal.firstOrNull { it.toString().equals(typed, ignoreCase = true) }
                ?: legal.firstOrNull { sanMatches(typed, moveToSan(board, it)) }
                ?: when (val parsed = parser.parse(typed, fen)) {
                    is ParseResult.Move -> legal.firstOrNull { it.toString().equals(parsed.uci, ignoreCase = true) }
                    else -> null
                }

            if (matched == null) {
                effect(GameEffect.Speak("$typed is not a legal move"))
                return@launch
            }

            val san = try { moveToSan(board, matched) } catch (_: Exception) { matched.toString() }
            val canonical = canonicalMappingPhrase(board, matched)
            if (raw.isNotBlank()) {
                saveWordTraining(raw, canonical)
            } else {
                store.appendHistory(
                    spoken = typed.lowercase(),
                    mapped = canonical,
                    category = "move_phrase",
                    source = "manual-typed"
                )
            }
            stage(san, matched.toString())
        }
    }

    /** Saves both full-phrase and piece-token mappings from manual correction. */
    private fun saveWordTraining(rawSpeech: String, canonicalMove: String) {
        val raw = rawSpeech.lowercase().trim().replace(Regex("\\s+"), " ")
        if (raw.isBlank()) return

        val existing = store.getMappings().toMutableMap()
        val movePhrase = canonicalMove.lowercase().trim().replace(Regex("\\s+"), " ")
        val square = movePhrase.takeLast(2).takeIf { it.matches(Regex("[a-h][1-8]")) } ?: ""
        val fileStr = square.firstOrNull()?.toString().orEmpty()
        val rankStr = square.lastOrNull()?.toString().orEmpty()
        val canonicalPiece = movePhrase.substringBefore(' ')

        fun saveTyped(spoken: String, mapped: String, source: String = "manual-confirm") {
            val s = spoken.lowercase().trim().replace(Regex("\\s+"), " ")
            val m = mapped.lowercase().trim().replace(Regex("\\s+"), " ")
            if (s.isBlank() || m.isBlank()) return
            existing[s] = m
            store.appendHistory(
                spoken = s,
                mapped = m,
                category = inferCategory(m),
                source = source
            )
        }

        // 1) Full phrase mapping ensures every failed phrase is trained.
        saveTyped(raw, movePhrase)

        // 2) Keep safe token-level mappings with type awareness.
        val tokens = raw.split(" ")

        val pieceAliases = setOf(
            "night", "nite", "knife", "horse", "be shop", "bee shop", "rock", "root", "pun", "porn", "prawn"
        )
        val fileAliases = mapOf("ef" to "f", "gee" to "g", "aitch" to "h", "eitch" to "h", "haitch" to "h", "ay" to "a", "ee" to "e", "bee" to "b", "see" to "c", "sea" to "c", "dee" to "d")
        val rankAliases = mapOf("one" to "1", "two" to "2", "three" to "3", "tree" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "ate" to "8")

        tokens.windowed(2, 1, partialWindows = false).forEach { pair ->
            val joined = pair.joinToString(" ")
            if (joined in pieceAliases) saveTyped(joined, canonicalPiece)
        }
        tokens.forEach { token ->
            if (token in pieceAliases) saveTyped(token, canonicalPiece)
            fileAliases[token]?.let { mappedFile ->
                if (mappedFile == fileStr) saveTyped(token, fileStr)
            }
            rankAliases[token]?.let { mappedRank ->
                if (mappedRank == rankStr) saveTyped(token, rankStr)
            }
            if (token.length == 2 && token[0] in 'a'..'h' && token[1] in '1'..'8') {
                if (token == "$fileStr$rankStr") saveTyped(token, token)
            }
        }

        store.saveMappings(existing)
    }

    private fun canonicalMappingPhrase(board: Board, move: Move): String {
        val piece = when (board.getPiece(move.from).pieceType) {
            PieceType.KING -> "king"
            PieceType.QUEEN -> "queen"
            PieceType.ROOK -> "rook"
            PieceType.BISHOP -> "bishop"
            PieceType.KNIGHT -> "knight"
            else -> "pawn"
        }
        return "$piece ${move.to.value().lowercase()}"
    }

    private fun sanMatches(typed: String, san: String): Boolean {
        fun normalize(value: String): String = value.lowercase().replace(Regex("[+#x=\\s-]"), "")
        return normalize(typed) == normalize(san)
    }

    private fun inferCategory(mapped: String): String {
        val m = mapped.lowercase().trim()
        return when {
            m in setOf("pawn", "knight", "bishop", "rook", "queen", "king") -> "piece"
            m.length == 1 && m[0] in 'a'..'h' -> "file"
            m.length == 1 && m[0] in '1'..'8' -> "rank"
            m.matches(Regex("^[a-h][1-8]$")) -> "square"
            m.matches(Regex("^(pawn|knight|bishop|rook|queen|king) [a-h][1-8]$")) -> "move_phrase"
            else -> "other"
        }
    }

    // ---- plumbing ----------------------------------------------------------
    private inline fun reduce(crossinline block: GameUiState.() -> GameUiState) {
        _state.value = _state.value.block()
    }
    private fun effect(e: GameEffect) { _effects.trySend(e) }

    private fun formatMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "${total / 60} minutes ${total % 60} seconds"
    }

    override fun onCleared() {
        listenJob?.cancel()
        eventsJob?.cancel()
        gameStateJob?.cancel()
        viewModelScope.launch { engine.stop() }
        tts.shutdown()
    }
}
