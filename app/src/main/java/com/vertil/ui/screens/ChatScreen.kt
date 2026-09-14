package com.vertil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.chat.ChatMessage
import com.vertil.chat.ChatRole
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSecondary
import com.vertil.ui.theme.VSurface

@Composable
fun ChatScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val state by vm.chat.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("VERTIL", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    state.activeModelName?.let { "LOCAL · $it" } ?: "LOCAL · sin modelo",
                    color = VPrimary, style = MaterialTheme.typography.labelMedium
                )
            }
            if (state.isGenerating) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = VPrimary, modifier = Modifier.size(20.dp))
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages) { msg -> MessageBubble(msg) }
        }

        // Input
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe a VERTIL…") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = VSurface,
                    unfocusedContainerColor = VSurface,
                    cursorColor = VPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            if (state.isGenerating) {
                IconButton(onClick = { vm.cancelGeneration() }) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cancelar", tint = VPrimary)
                }
            } else {
                IconButton(onClick = {
                    if (input.isNotBlank()) {
                        vm.sendMessage(input.trim()); input = ""
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = VPrimary)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) VPrimary.copy(alpha = 0.18f) else VSurface
    val textColor = if (msg.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(msg.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
                if (!isUser && (msg.tokensGenerated > 0 || msg.speedLabel != null)) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (msg.tokensGenerated > 0) append("${msg.tokensGenerated} tokens")
                            if (msg.speedLabel != null) {
                                if (isNotEmpty()) append(" · ")
                                append(msg.speedLabel)
                            }
                            if (msg.durationMs > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${msg.durationMs}ms")
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
