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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface

@Composable
fun AutomationsScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val rules by vm.automations.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Automatizaciones", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${rules.size} reglas · ejecución v1.1", color = VPrimary, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { showCreate = true }) { Text("Nueva") }
        }
        if (rules.isEmpty()) {
            EmptyBox(title = "Sin automatizaciones", msg = "Crea reglas para que VERTIL organice archivos automáticamente.\n\nNota: la ejecución automática se activa en v1.1; v1.0 permite crear y persistir reglas.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rules) { rule ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = VSurface)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rule.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Switch(checked = rule.enabled, onCheckedChange = { vm.toggleAutomation(rule.id, it) })
                            }
                            Text(rule.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Row(modifier = Modifier.padding(top = 6.dp)) {
                                OutlinedButton(onClick = { vm.deleteAutomation(rule.id) }) { Text("Eliminar") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var folder by remember { mutableStateOf("") }
        var ext by remember { mutableStateOf("pdf") }
        var dst by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nueva regla") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Carpeta origen") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = ext, onValueChange = { ext = it }, label = { Text("Extensión (ej: pdf)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = dst, onValueChange = { dst = it }, label = { Text("Carpeta destino") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && folder.isNotBlank() && ext.isNotBlank() && dst.isNotBlank()) {
                        vm.createAutomation(name, folder, ext, dst)
                    }
                    showCreate = false
                }) { Text("Crear", color = VPrimary) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }
}
