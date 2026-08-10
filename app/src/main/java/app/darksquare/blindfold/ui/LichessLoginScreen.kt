package app.darksquare.blindfold.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LichessLoginScreen(
    onLogin: (String) -> Unit,
    onSkip: () -> Unit,
    errorText: String?
) {
    var token by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0F1720), Color(0xFF1A2D42), Color(0xFF0F1720))))
        ) {
            Surface(color = Color(0x7A1D2A38), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Lichess Login",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F7FF)
                    )
                    Text(
                        "Paste your Lichess API token to enable online play.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFB0BEC5)
                    )

                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Lichess API Token", color = Color(0xFFC9D8E6)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFF1F7FF)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF89C6E3),
                            unfocusedBorderColor = Color(0xFF4E6C82),
                            cursorColor = Color(0xFFBFE8FF),
                            focusedTextColor = Color(0xFFF1F7FF),
                            unfocusedTextColor = Color(0xFFF1F7FF)
                        )
                    )

                    errorText?.let {
                        Text(it, color = Color(0xFFEF9A9A), style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = { onLogin(token) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = token.isNotBlank()
                    ) {
                        Text("Login", color = Color(0xFFF4F9FF))
                    }

                    TextButton(onClick = onSkip) {
                        Text("Continue without Lichess", color = Color(0xFFD7E6F4))
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You can still play vs engine and train voice without logging in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF90A4AE),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
