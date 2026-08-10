package app.darksquare.blindfold.ui.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CalibrationScreen(vm: CalibrationViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.isDone) {
        if (s.isDone) onDone()
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0F1720), Color(0xFF1A2D42), Color(0xFF10171E))))
        ) {
            Surface(modifier = Modifier.fillMaxSize().padding(20.dp), color = Color(0x7A1D2A38), shape = MaterialTheme.shapes.extraLarge) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Voice Calibration",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F7FF)
                    )
                    Text(
                        "Speak each word so the app learns your pronunciation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF90A4AE),
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.index.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${s.index + 1} of ${s.total}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF90A4AE))

                    Spacer(Modifier.height(8.dp))

                    Surface(
                        color = Color(0x80313E4B),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Say this out loud:",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF90A4AE)
                            )
                            Text(
                                s.display,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = Color(0xFFF4F9FF)
                            )
                            if (s.heard.isNotBlank()) {
                                Text(
                                    "Heard: \"${s.heard}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF80CBC4)
                                )
                            }
                        }
                    }

                    if (s.isListening) {
                        CircularProgressIndicator()
                        Text("Listening…", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE6F0F8))
                    } else if (s.heard.isBlank()) {
                        Button(onClick = vm::startListening, modifier = Modifier.fillMaxWidth()) {
                            Text("🎤  Speak")
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = vm::startListening, modifier = Modifier.weight(1f)) {
                                Text("Retry")
                            }
                            Button(onClick = vm::accept, modifier = Modifier.weight(1f)) {
                                Text("Accept")
                            }
                        }
                    }

                    TextButton(onClick = vm::skip) {
                        Text("Skip this word", color = Color(0xFF90A4AE))
                    }
                }
            }
        }
    }
}
