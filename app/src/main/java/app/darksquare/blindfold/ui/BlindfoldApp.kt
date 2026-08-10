package app.darksquare.blindfold.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.darksquare.blindfold.data.MappingHistoryEntry
import app.darksquare.blindfold.ui.game.EngineDifficulty
import app.darksquare.blindfold.ui.game.GameIntent
import app.darksquare.blindfold.ui.game.GameUiState
import app.darksquare.blindfold.ui.game.GameViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BlindfoldApp(vm: GameViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showManualInput by remember { mutableStateOf(false) }
    var showMappings by remember { mutableStateOf(false) }
    var mappings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mappingHistory by remember { mutableStateOf<List<MappingHistoryEntry>>(emptyList()) }

    val appColors = darkColorScheme(
        primary = Color(0xFF6AC1D8),
        secondary = Color(0xFF8BC34A),
        background = Color(0xFF0E141C),
        surface = Color(0xFF161F2A),
        surfaceVariant = Color(0xFF1F2B39),
        onSurfaceVariant = Color(0xFFAEBBC9)
    )

    MaterialTheme(colorScheme = appColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B1118), Color(0xFF142235), Color(0xFF0E141C))
                    )
                )
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Header(state, vm::onIntent, onManual = { showManualInput = true })

                TopActionsRow(
                    onManual = { showManualInput = true },
                    onMappings = {
                        mappings = vm.getMappingsSnapshot()
                        mappingHistory = vm.getMappingHistorySnapshot()
                        showMappings = true
                    }
                )

                SectionCard {
                    ToggleRow(state, vm::onIntent)
                    if (state.mode == GameUiState.Mode.Lichess) {
                        Spacer(Modifier.height(6.dp))
                        val status = when {
                            state.onlineSearching -> "Searching for opponent..."
                            !state.onlineStatus.isNullOrBlank() -> state.onlineStatus
                            state.onlineGameId != null -> "Connected"
                            else -> "Not connected"
                        }
                        Text("Online: $status", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0DEE8))
                    }
                }

                StageBanner(state)
                Crossfade(targetState = state.blindfoldStrict, animationSpec = tween(400), label = "board_panel") { isBlindfolded ->
                    if (isBlindfolded) BlindfoldPanel(state)
                    else BoardWithEvalBar(state)
                }
                if (!state.blindfoldStrict) TurnRow(state)
                AnimatedVisibility(
                    visible = state.moves.isNotEmpty(),
                    enter = fadeIn(tween(400)) + expandVertically(),
                    exit  = fadeOut(tween(200)) + shrinkVertically()
                ) {
                    MoveList(state)
                }
            }
        }
    }

    // Manual-input dialog: appears when voice recognition fails or user taps Manual Move.
    val dialogRaw = state.rawFailedSpeech ?: if (showManualInput) "" else null
    dialogRaw?.let { raw ->
        ManualInputDialog(
            rawSpeech = raw,
            onConfirm = { piece, file, rank ->
                showManualInput = false
                vm.onIntent(GameIntent.ManualMove(piece, file, rank))
            },
            onConfirmTyped = { moveText ->
                showManualInput = false
                vm.onIntent(GameIntent.ManualMoveText(moveText))
            },
            onDismiss = {
                showManualInput = false
                vm.onIntent(GameIntent.DismissManualInput)
            }
        )
    }

    if (showMappings) {
        MappingDataDialog(
            mappings = mappings,
            history = mappingHistory,
            onSave = { spoken, mapped ->
                vm.upsertMapping(spoken, mapped)
                mappings = vm.getMappingsSnapshot()
                mappingHistory = vm.getMappingHistorySnapshot()
            },
            onDismiss = { showMappings = false }
        )
    }
}

@Composable
private fun TopActionsRow(onManual: () -> Unit, onMappings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onManual, modifier = Modifier.weight(1f)) { Text("Manual Move") }
        OutlinedButton(onClick = onMappings, modifier = Modifier.weight(1f)) {
            Text("Mappings")
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color(0x80213043),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x334EB6D9)),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun BoardWithEvalBar(s: GameUiState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EvalBar(state = s, modifier = Modifier.fillMaxWidth().height(26.dp))
        Text(
            "Engine: ${s.engineElo} | target depth ${EngineDifficulty.depthForRating(s.engineElo)} | reached ${s.eval?.depth ?: 0}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB8C7D6)
        )
        ChessBoard(
            fen = s.fen,
            lastMoveUci = s.moves.lastOrNull()?.uci,
            boardTheme = s.boardTheme,
            pieceTheme = s.pieceTheme,
        )
    }
}

@Composable
private fun EvalBar(state: GameUiState, modifier: Modifier = Modifier) {
    val whiteAdvPawns = remember(state.eval) {
        val ev = state.eval
        when {
            ev == null -> 0.0
            ev.mateIn != null -> if (ev.mateIn > 0) 10.0 else -10.0
            ev.cp != null -> ev.cp / 100.0
            else -> 0.0
        }
    }
    val whitePct = remember(whiteAdvPawns) {
        // Map roughly +/-8 pawns to the full bar width.
        ((whiteAdvPawns + 8.0) / 16.0).toFloat().coerceIn(0.02f, 0.98f)
    }
    val evalText = remember(state.eval, whiteAdvPawns) {
        val ev = state.eval
        when {
            ev == null -> "0.0"
            ev.mateIn != null -> {
                val sign = if (whiteAdvPawns >= 0) "+" else "-"
                "${sign}M${kotlin.math.abs(ev.mateIn)}"
            }
            else -> {
                val value = String.format("%+.1f", whiteAdvPawns)
                if (value == "-0.0") "0.0" else value
            }
        }
    }

    Surface(modifier = modifier, color = Color(0xFF151515), shape = MaterialTheme.shapes.small) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(whitePct).fillMaxHeight().background(Color(0xFFF5F5F5)))
                Box(Modifier.weight(1f - whitePct).fillMaxHeight().background(Color(0xFF121212)))
            }
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0x7A0F1720),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    evalText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFFF2F7FD),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable private fun Header(s: GameUiState, on: (GameIntent) -> Unit, onManual: () -> Unit) {
    Surface(
        color = Color(0x772A3A4F),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x335DCBE8)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("♟", fontSize = 34.sp, color = Color(0xFF90CAF9), modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Blindfold Chess",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFFF3F7FB)
                )
                Text(
                    if (s.authenticated) "● Lichess connected" else "○ Lichess offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.authenticated) Color(0xFF4CAF50) else Color(0xFF78909C)
                )
            }
            SettingsMenu(s = s, on = on, onManual = onManual)
        }
    }
}

@Composable
private fun SettingsMenu(s: GameUiState, on: (GameIntent) -> Unit, onManual: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showDifficulty by remember { mutableStateOf(false) }
    var showBoardTheme by remember { mutableStateOf(false) }
    var showPieceTheme by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color(0xFFE3EEF7))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Mode") }, onClick = {
                open = false
                showMode = true
            })
            if (s.mode == GameUiState.Mode.Stockfish) {
                DropdownMenuItem(text = { Text("Engine Difficulty") }, onClick = {
                    open = false
                    showDifficulty = true
                })
            }
            DropdownMenuItem(text = { Text("Board Theme") }, onClick = {
                open = false
                showBoardTheme = true
            })
            DropdownMenuItem(text = { Text("Piece Style") }, onClick = {
                open = false
                showPieceTheme = true
            })
            if (s.mode == GameUiState.Mode.Lichess) {
                if (s.onlineGameId == null) {
                    DropdownMenuItem(text = { Text("Find Match") }, onClick = {
                        open = false
                        on(GameIntent.StartOnlineMatch)
                    })
                } else {
                    DropdownMenuItem(text = { Text("Leave Match") }, onClick = {
                        open = false
                        on(GameIntent.LeaveOnlineGame)
                    })
                }
            }
            DropdownMenuItem(text = { Text("Manual Move") }, onClick = {
                open = false
                onManual()
            })
        }
    }

    if (showMode) {
        SelectionDialog(
            title = "Mode",
            options = GameUiState.Mode.values().map { it.name },
            selected = s.mode.name,
            onSelect = { name -> on(GameIntent.SetMode(GameUiState.Mode.valueOf(name))) },
            onDismiss = { showMode = false }
        )
    }

    if (showDifficulty) {
        SelectionDialog(
            title = "Engine Difficulty",
            options = EngineDifficulty.ratingDepthTable.map { (rating, _) -> EngineDifficulty.optionLabel(rating) },
            selected = EngineDifficulty.optionLabel(s.engineElo),
            onSelect = { option ->
                on(GameIntent.SetEngineElo(EngineDifficulty.parseRatingFromOption(option)))
            },
            onDismiss = { showDifficulty = false }
        )
    }
    if (showBoardTheme) {
        SelectionDialog(
            title = "Board Theme",
            options = GameUiState.BoardTheme.values().map { it.name },
            selected = s.boardTheme.name,
            onSelect = { name -> on(GameIntent.SetBoardTheme(GameUiState.BoardTheme.valueOf(name))) },
            onDismiss = { showBoardTheme = false }
        )
    }
    if (showPieceTheme) {
        SelectionDialog(
            title = "Piece Style",
            options = GameUiState.PieceTheme.values().map { it.name },
            selected = s.pieceTheme.name,
            onSelect = { name -> on(GameIntent.SetPieceTheme(GameUiState.PieceTheme.valueOf(name))) },
            onDismiss = { showPieceTheme = false }
        )
    }
}

@Composable
private fun SelectionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { item ->
                    FilterChip(
                        selected = item == selected,
                        onClick = {
                            onSelect(item)
                            onDismiss()
                        },
                        label = { Text(item) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable private fun ToggleRow(s: GameUiState, on: (GameIntent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (s.blindfoldStrict) "Blindfold Mode" else "Board Visible", color = Color(0xFFEAF2FA))
        Switch(checked = s.blindfoldStrict, onCheckedChange = { on(GameIntent.ToggleBlindfold) })
    }
}

@Composable private fun StageBanner(s: GameUiState) {
    val targetColor = when (s.stage) {
        GameUiState.Stage.Idle         -> Color(0xFF263238)
        GameUiState.Stage.Listening    -> Color(0xFF1B5E20)
        GameUiState.Stage.Staged       -> Color(0xFFE65100)
        GameUiState.Stage.Transmitting -> Color(0xFF0D47A1)
    }
    val text = when (s.stage) {
        GameUiState.Stage.Idle         -> "Hold Vol ↓ to speak a move"
        GameUiState.Stage.Listening    -> "Listening…"
        GameUiState.Stage.Staged       -> "Confirm: ${s.pendingMoveSan ?: ""}  —  Vol ↓ to confirm"
        GameUiState.Stage.Transmitting -> "Sending move…"
    }
    val bgColor by animateColorAsState(targetColor, animationSpec = tween(300), label = "stage_color")
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "dot_pulse")
    val dotColor = if (s.stage == GameUiState.Stage.Listening)
        Color(0xFF69F0AE).copy(alpha = pulseAlpha) else Color.White
    Surface(color = bgColor, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (s.stage == GameUiState.Stage.Idle) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(18.dp))
            } else {
                val sym = when (s.stage) {
                    GameUiState.Stage.Listening    -> "●"
                    GameUiState.Stage.Staged       -> "→"
                    GameUiState.Stage.Transmitting -> "⟳"
                    else -> ""
                }
                Text(sym, color = dotColor, fontSize = 16.sp)
            }
            Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable private fun BlindfoldPanel(s: GameUiState) {
    val moveNum = (s.moves.size + 1) / 2
    val borderColor by animateColorAsState(
        if (s.whiteToMove) Color(0xFF90CAF9) else Color(0xFFCE93D8),
        animationSpec = tween(600), label = "turn_border"
    )
    val borderWidth by animateFloatAsState(
        if (s.stage == GameUiState.Stage.Staged) 3f else 1.5f, tween(250), label = "border_w"
    )
    Surface(
        color = Color(0xFF1A1A2E),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(borderWidth.dp, borderColor, MaterialTheme.shapes.large)
    ) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BLINDFOLD", color = Color(0xFF607D8B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                if (s.whiteToMove) "White to move" else "Black to move",
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold
            )
            if (moveNum > 0) {
                Text("Move $moveNum", color = Color(0xFF78909C), fontSize = 14.sp)
            }
            s.moves.lastOrNull()?.let { last ->
                Spacer(Modifier.height(16.dp))
                Text("Last: ${last.san}", color = Color(0xFF80CBC4), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            s.eval?.let { e ->
                Spacer(Modifier.height(12.dp))
                val evalStr = when {
                    e.mateIn != null -> if (e.mateIn > 0) "Mate in ${e.mateIn}" else "Mated in ${-e.mateIn}"
                    e.cp != null     -> "${if (e.cp >= 0) "+" else ""}${"%.1f".format(e.cp / 100.0)}"
                    else             -> ""
                }
                if (evalStr.isNotBlank()) {
                    Text(evalStr, color = Color(0xFF66BB6A), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "target depth ${EngineDifficulty.depthForRating(s.engineElo)} | reached ${e.depth}",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }
            }
            s.lastError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xFFEF9A9A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChessBoard(
    fen: String,
    lastMoveUci: String? = null,
    boardTheme: GameUiState.BoardTheme,
    pieceTheme: GameUiState.PieceTheme,
) {
    val pieces   = remember(fen) { parseFen(fen) }
    val fromIdx  = remember(lastMoveUci) { lastMoveUci?.let { uciSqIdx(it.take(2)) } ?: -1 }
    val toIdx    = remember(lastMoveUci) { lastMoveUci?.let { uciSqIdx(it.drop(2).take(2)) } ?: -1 }

    val palette = when (boardTheme) {
        GameUiState.BoardTheme.Classic -> BoardPalette(
            lightSq = Color.White,
            darkSq = Color(0xFF616161),
            frame = Color(0xFF212121),
            labelOnLight = Color(0xFF424242),
            labelOnDark = Color(0xFFEEEEEE),
        )
        GameUiState.BoardTheme.Brown -> BoardPalette(
            lightSq = Color(0xFFF0D9B5),
            darkSq = Color(0xFFB58863),
            frame = Color(0xFF5D4037),
            labelOnLight = Color(0xFF7A5A40),
            labelOnDark = Color(0xFFF4E7D2),
        )
        GameUiState.BoardTheme.Blue -> BoardPalette(
            lightSq = Color(0xFFEAF3FF),
            darkSq = Color(0xFF476C9B),
            frame = Color(0xFF1D3557),
            labelOnLight = Color(0xFF34506F),
            labelOnDark = Color(0xFFE3EEFF),
        )
        GameUiState.BoardTheme.Forest -> BoardPalette(
            lightSq = Color(0xFFEEF6E8),
            darkSq = Color(0xFF5D7F52),
            frame = Color(0xFF2F4F2F),
            labelOnLight = Color(0xFF3E5B39),
            labelOnDark = Color(0xFFE7F4E0),
        )
    }
    val highlightColor = Color(0xFFFFCA28)  // amber for last move

    Surface(
        color = palette.frame,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f)) {
                    for (file in 0..7) {
                        val sqIdx    = rank * 8 + file
                        val isLight  = (rank + file) % 2 != 0
                        val piece    = pieces[sqIdx]
                        val baseColor = if (isLight) palette.lightSq else palette.darkSq
                        val lblColor  = if (isLight) palette.labelOnLight else palette.labelOnDark
                        val isLastMove = sqIdx == fromIdx || sqIdx == toIdx

                        Box(
                            Modifier.weight(1f).fillMaxHeight().background(baseColor),
                            contentAlignment = Alignment.Center
                        ) {
                            // Last-move amber overlay
                            if (isLastMove) {
                                Box(Modifier.fillMaxSize().background(highlightColor.copy(alpha = 0.45f)))
                            }
                            if (file == 0) Text(
                                "${rank + 1}",
                                Modifier.align(Alignment.TopStart).padding(2.dp),
                                fontSize = 8.sp, color = lblColor, fontWeight = FontWeight.SemiBold
                            )
                            if (rank == 0) Text(
                                "abcdefgh"[file].toString(),
                                Modifier.align(Alignment.BottomEnd).padding(2.dp),
                                fontSize = 8.sp, color = lblColor, fontWeight = FontWeight.SemiBold
                            )
                            if (piece != ' ') {
                                val isWhite = piece.isUpperCase()
                                when (pieceTheme) {
                                    GameUiState.PieceTheme.Classic -> {
                                        val glyph = pieceToUnicodeClassic(piece)
                                        val mainColor = if (isWhite) Color(0xFFFFFFFF) else Color(0xFF111111)
                                        val outlineColor = if (isWhite) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
                                        val glowColor = if (isWhite) Color(0x55FFFFFF) else Color(0x22FFFFFF)
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                glyph,
                                                fontSize = 32.sp,
                                                fontFamily = FontFamily.Serif,
                                                fontWeight = FontWeight.Bold,
                                                color = outlineColor,
                                                style = TextStyle(shadow = Shadow(
                                                    color = if (isWhite) Color(0xDD000000) else Color(0x99FFFFFF),
                                                    offset = Offset(0.8f, 1.2f),
                                                    blurRadius = 3f
                                                ))
                                            )
                                            Text(
                                                glyph,
                                                fontSize = 30.sp,
                                                fontFamily = FontFamily.Serif,
                                                fontWeight = FontWeight.Bold,
                                                color = mainColor
                                            )
                                            Text(
                                                glyph,
                                                modifier = Modifier.offset(x = (-0.5).dp, y = (-0.5).dp),
                                                fontSize = 27.sp,
                                                fontFamily = FontFamily.Serif,
                                                fontWeight = FontWeight.Bold,
                                                color = glowColor
                                            )
                                        }
                                    }
                                    GameUiState.PieceTheme.Solid -> {
                                        Text(
                                            pieceToUnicodeSolid(piece),
                                            fontSize = 30.sp,
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isWhite) Color.White else Color(0xFF0D0D0D),
                                            style = TextStyle(shadow = Shadow(
                                                color = if (isWhite) Color(0xDD000000) else Color(0xAAFFFFFF),
                                                offset = Offset(1f, 1.5f),
                                                blurRadius = 4f
                                            ))
                                        )
                                    }
                                    GameUiState.PieceTheme.Neo -> {
                                        Text(
                                            pieceToUnicodeSolid(piece),
                                            fontSize = 30.sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isWhite) Color(0xFFF9FBFF) else Color(0xFF0B0F14),
                                            style = TextStyle(shadow = Shadow(
                                                color = if (isWhite) Color(0xAA000000) else Color(0x66FFFFFF),
                                                offset = Offset(0.8f, 1f),
                                                blurRadius = 2f
                                            ))
                                        )
                                    }
                                    GameUiState.PieceTheme.Outline -> {
                                        Text(
                                            pieceToUnicodeClassic(piece),
                                            fontSize = 30.sp,
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isWhite) Color(0xFFEFF4FF) else Color(0xFF0E1116),
                                            style = TextStyle(shadow = Shadow(
                                                color = if (isWhite) Color(0xCC233142) else Color(0x88E6EEF7),
                                                offset = Offset(0.6f, 0.8f),
                                                blurRadius = 1.2f
                                            ))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun uciSqIdx(sq: String): Int {
    if (sq.length < 2) return -1
    val file = sq[0] - 'a'
    val rank = sq[1] - '1'
    return if (file in 0..7 && rank in 0..7) rank * 8 + file else -1
}

@Composable
private fun ManualInputDialog(
    rawSpeech: String,
    onConfirm: (piece: String, file: Char, rank: Int) -> Unit,
    onConfirmTyped: (moveText: String) -> Unit,
    onDismiss: () -> Unit
) {
    val pieces = listOf("Pawn", "Knight", "Bishop", "Rook", "Queen", "King")
    var selPiece by remember { mutableStateOf("") }
    var selFile  by remember { mutableStateOf(' ') }
    var selRank  by remember { mutableStateOf(0) }
    var typedMove by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What did you mean?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rawSpeech.isNotBlank())
                    Text("Heard: \"$rawSpeech\"", style = MaterialTheme.typography.bodySmall, color = Color(0xFF78909C))

                Text("Piece", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(pieces) { p ->
                        FilterChip(selected = selPiece == p, onClick = { selPiece = p },
                            label = { Text(p, fontSize = 11.sp) })
                    }
                }

                Text("File  (a – h)", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(('a'..'h').toList()) { f ->
                        FilterChip(selected = selFile == f, onClick = { selFile = f },
                            label = { Text("$f", fontSize = 11.sp) })
                    }
                }

                Text("Rank  (1 – 8)", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items((1..8).toList()) { r ->
                        FilterChip(selected = selRank == r, onClick = { selRank = r },
                            label = { Text("$r", fontSize = 11.sp) })
                    }
                }

                Text("Or type move manually", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE2ECF5))
                OutlinedTextField(
                    value = typedMove,
                    onValueChange = { typedMove = it },
                    label = { Text("e4, Nf3, g1f3, knight f3") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onConfirmTyped(typedMove) },
                    enabled = typedMove.isNotBlank()
                ) { Text("Use Typed Move") }
                TextButton(
                    onClick = { onConfirm(selPiece, selFile, selRank) },
                    enabled = selPiece.isNotBlank() && selFile != ' ' && selRank != 0
                ) { Text("Confirm") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable private fun TurnRow(s: GameUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (s.moves.isEmpty()) "Starting position" else "Move ${(s.moves.size + 1) / 2}",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFD8E4F1)
        )
        Surface(
            color = if (s.whiteToMove) Color(0xFF46688A) else Color(0xFF2D2D2D),
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                if (s.whiteToMove) "White to move" else "Black to move",
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                color = Color(0xFFF5F9FF),
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable private fun MoveList(s: GameUiState) {
    if (s.moves.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val pairs = s.moves.chunked(2)
            pairs.forEachIndexed { i, pair ->
                val isLast = i == pairs.lastIndex
                Surface(
                    color = if (isLast) Color(0xFF80CBC4).copy(alpha = 0.15f)
                            else if (i % 2 == 0) Color.Transparent
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}.", Modifier.width(30.dp), color = Color(0xFF757575), fontSize = 13.sp)
                        Text(pair[0].san, Modifier.weight(1f), fontSize = 13.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLast) Color(0xFF80CBC4) else Color(0xFFE6EEF8))
                        Text(if (pair.size > 1) pair[1].san else "", Modifier.weight(1f), fontSize = 13.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLast && pair.size > 1) Color(0xFF80CBC4) else Color(0xFFE6EEF8))
                    }
                }
            }
        }
    }
}

private fun parseFen(fen: String): CharArray {
    val board = CharArray(64) { ' ' }
    var rank = 0
    var file = 0
    for (char in fen) {
        if (char == ' ') break
        if (char == '/')             { rank++; file = 0 }
        else if (char in '1'..'8')  file += char - '0'
        else if (rank < 8 && file < 8) { board[(7 - rank) * 8 + file] = char; file++ }
    }
    return board
}

private data class BoardPalette(
    val lightSq: Color,
    val darkSq: Color,
    val frame: Color,
    val labelOnLight: Color,
    val labelOnDark: Color,
)

// White/black glyph sets for a classic Staunton look (closer to lichess pieces).
private fun pieceToUnicodeClassic(piece: Char): String = when (piece) {
    'K' -> "♔"; 'Q' -> "♕"; 'R' -> "♖"; 'B' -> "♗"; 'N' -> "♘"; 'P' -> "♙"
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟"
    else -> ""
}

@Composable
private fun MappingDataDialog(
    mappings: Map<String, String>,
    history: List<MappingHistoryEntry>,
    onSave: (spoken: String, mapped: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var spoken by remember { mutableStateOf("") }
    var mapped by remember { mutableStateOf("") }
    var selPiece by remember { mutableStateOf("") }
    var selFile by remember { mutableStateOf(' ') }
    var selRank by remember { mutableStateOf(0) }
    val pieces = listOf("pawn", "knight", "bishop", "rook", "queen", "king")

    fun builtMove(): String {
        return if (selPiece.isNotBlank() && selFile != ' ' && selRank != 0) "$selPiece ${selFile.lowercaseChar()}$selRank" else ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Mappings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = spoken, onValueChange = { spoken = it }, label = { Text("Heard text") })
                Text("Mapped to chess move", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE2ECF5))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(pieces) { piece ->
                        FilterChip(selected = selPiece == piece, onClick = { selPiece = piece }, label = { Text(piece) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(('a'..'h').toList()) { file ->
                        FilterChip(selected = selFile == file, onClick = { selFile = file }, label = { Text("$file") })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items((1..8).toList()) { rank ->
                        FilterChip(selected = selRank == rank, onClick = { selRank = rank }, label = { Text("$rank") })
                    }
                }
                OutlinedTextField(
                    value = mapped,
                    onValueChange = { mapped = it },
                    label = { Text("Or type mapped move manually") },
                    supportingText = { Text("Example: pawn e4 or knight f3") }
                )
                Button(onClick = {
                    onSave(spoken, mapped.ifBlank { builtMove() })
                    spoken = ""
                    mapped = ""
                    selPiece = ""
                    selFile = ' '
                    selRank = 0
                }, enabled = spoken.isNotBlank() && (mapped.isNotBlank() || builtMove().isNotBlank())) {
                    Text("Save mapping")
                }
                Spacer(Modifier.height(4.dp))
                Text("Current mappings", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE2ECF5))
                Column(
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (mappings.isEmpty()) Text("No mappings yet", color = Color(0xFFB0BEC5))
                    mappings.toSortedMap().forEach { (k, v) ->
                        Text("$k  →  $v", color = Color(0xFFE8F0F7), fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Mapping history", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE2ECF5))
                Column(
                    modifier = Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (history.isEmpty()) {
                        Text("No history yet", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                    } else {
                        history.forEach { item ->
                            val ts = remember(item.timestampMs) {
                                if (item.timestampMs <= 0L) "Earlier"
                                else SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
                            }
                            Text(
                                "[$ts] ${item.source} • ${item.category} • ${item.spoken} → ${item.mapped}",
                                color = Color(0xFFD7E5F2),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun pieceToUnicodeSolid(piece: Char): String = when (piece.lowercaseChar()) {
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟"
    else -> ""
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("♟", fontSize = 72.sp, color = Color(0xFF90A4AE))
                Spacer(Modifier.height(24.dp))
                Text(
                    "Blindfold Chess",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Microphone access is needed so you can speak your moves hands-free.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(40.dp))
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Microphone Access")
                }
            }
        }
    }
}
