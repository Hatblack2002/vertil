package com.vertil.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface
import com.vertil.ui.theme.VSurfaceVariant

@Composable
fun ModelsScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val models by vm.models.collectAsState()
    val home by vm.home.collectAsState()
    val ctx = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "modelo"
            vm.importModel(ctx, uri, name)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Modelos locales", color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${models.size} modelos · runtime ONNX", color = VPrimary, style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = VPrimary, contentColor = Color.Black)
            ) { Text("Importar") }
        }

        if (models.isEmpty()) {
            EmptyBox(title = "No hay modelos importados",
                msg = "Importa un archivo .onnx para activar la inferencia local.\n\nNota: los modelos GGUF y TFLite están documentados como pendientes v1.1.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(models) { model -> ModelCard(model, vm, isActive = model.id == home.activeModel?.id) }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo, vm: VertilViewModel, isActive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${model.sizeHuman} · ${model.format.label}${model.quantization?.let { " · $it" } ?: ""}",
                        color = VPrimary, style = MaterialTheme.typography.labelMedium)
                }
                if (isActive) {
                    Box(modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(VPrimary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("ACTIVO", color = VPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Estado: ${stateLabel(model.state)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            model.lastErrorMessage?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    OutlinedButton(onClick = { vm.setActiveModel(model.id) }) { Text("Activar") }
                }
                OutlinedButton(onClick = { vm.loadActiveModel() }) { Text("Cargar") }
                OutlinedButton(onClick = { vm.verifyHash(model.id) }) { Text("Hash") }
                OutlinedButton(onClick = { vm.deleteModel(model.id) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun stateLabel(s: ModelState): String = when (s) {
    ModelState.INSTALLED -> "Instalado"
    ModelState.LOADING -> "Cargando…"
    ModelState.READY -> "Listo"
    ModelState.GENERATING -> "Generando…"
    ModelState.ERROR -> "Error"
    ModelState.UNLOADING -> "Descargando…"
}

@Composable
fun EmptyBox(title: String, msg: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
