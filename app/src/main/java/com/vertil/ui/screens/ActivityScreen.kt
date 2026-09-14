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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val activity by vm.activity.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Actividad", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${activity.size} entradas", color = VPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (activity.isNotEmpty()) {
                OutlinedButton(onClick = { vm.clearActivity() }) { Text("Limpiar") }
            }
        }
        if (activity.isEmpty()) {
            EmptyBox(title = "Sin actividad", msg = "Las acciones ejecutadas mediante herramientas aparecerán aquí.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(activity) { entry ->
                    val df = SimpleDateFormat("HH:mm:ss", Locale.US)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = VSurface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row {
                                Text("${entry.icon}  ", color = VPrimary, fontFamily = FontFamily.Monospace)
                                Text(entry.operation, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(df.format(Date(entry.timestamp)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(entry.message, color = if (entry.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            entry.source?.let { Text("Origen: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
                            entry.destination?.let { Text("Destino: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
