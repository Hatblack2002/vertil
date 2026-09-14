package com.vertil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.permissions.PermissionCapability
import com.vertil.permissions.PermissionLevel
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface

@Composable
fun PermissionsScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val pending by vm.pendingRequests.collectAsState()
    val levels = PermissionLevel.values()
    val capabilities = PermissionCapability.values()

    Column(modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)) {
        Text("Permisos", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        Text("Sistema de seguridad real (no prompt). ${pending.size} solicitudes pendientes.", color = VPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(levels) { lvl ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = VSurface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Nivel ${lvl.priority} · ${lvl.label}", color = VPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (lvl.requiresExplicitConfirm) {
                            Text("⚠ Requiere confirmación explícita del usuario.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        capabilities.filter { it.level == lvl }.forEach { cap ->
                            Text("• ${cap.label}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (pending.isNotEmpty()) {
        val req = pending.first()
        AlertDialog(
            onDismissRequest = { vm.confirmPermission(req.id, false) },
            title = { Text("VERTIL solicita permiso") },
            text = {
                Column {
                    Text("Herramienta: ${req.toolId}", color = VPrimary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Capacidades requeridas:", color = MaterialTheme.colorScheme.onSurface)
                    req.capabilities.forEach { Text("• ${it.label} (nivel ${it.level.priority})", color = MaterialTheme.colorScheme.onSurface) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmPermission(req.id, true) }) { Text("Permitir", color = VPrimary) } },
            dismissButton = { TextButton(onClick = { vm.confirmPermission(req.id, false) }) { Text("Denegar", color = MaterialTheme.colorScheme.error) } }
        )
    }
}
