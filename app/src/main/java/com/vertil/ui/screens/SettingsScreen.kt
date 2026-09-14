package com.vertil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.BuildConfig
import com.vertil.core.identity.VertilIdentity
import com.vertil.core.prompt.SystemPrompt
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface

@Composable
fun SettingsScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    var advanced by remember { mutableStateOf(false) }
    val runtime = com.vertil.di.ServiceLocator.modelManager.activeRuntime.value
    val runtimeInfo = runtime.getRuntimeInfo()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text("Ajustes", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = VSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Acerca de", color = VPrimary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Identidad: ${VertilIdentity.NAME}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Desarrollador: ${VertilIdentity.DEVELOPER}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Versión del core: ${com.vertil.core.VertilCore.CORE_VERSION}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Policy version: ${SystemPrompt.POLICY_VERSION}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tool API: ${SystemPrompt.TOOL_API_VERSION}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text("Application ID: ${BuildConfig.APPLICATION_ID}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = VSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row {
                        Text("Modo avanzado", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                        Switch(checked = advanced, onCheckedChange = { advanced = it })
                    }
                    if (advanced) {
                        Spacer(Modifier.height(8.dp))
                        runtimeInfo.forEach { (k, v) ->
                            Text("$k: $v", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
