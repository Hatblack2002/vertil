package com.vertil.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.core.VertilResult
import com.vertil.storage.FileEntry
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(vm: VertilViewModel, contentPadding: PaddingValues = PaddingValues()) {
    val ctx = LocalContext.current
    var currentPath by remember { mutableStateOf("internal://${ctx.filesDir.absolutePath}") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persistencia protegida (especificación §5): SecurityException del
            // proveedor NO debe cerrar la app; la sesión actual sigue funcionando.
            com.vertil.model.importer.UriPermissions.tryTakePersistable(
                ctx, uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            currentPath = uri.toString()
        }
    }

    LaunchedEffect(currentPath) {
        withContext(Dispatchers.IO) {
            val result = vm.let { com.vertil.di.ServiceLocator.fileEngine.list(currentPath) }
            entries = (result as? VertilResult.Success)?.value ?: emptyList()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(VBg).padding(contentPadding)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Archivos", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(currentPath.takeLast(60), color = VPrimary, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("SAF") }
        }
        OutlinedButton(
            onClick = { currentPath = "internal://${ctx.filesDir.absolutePath}" },
            modifier = Modifier.padding(horizontal = 16.dp)
        ) { Text("Internal storage") }
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            EmptyBox(title = "Carpeta vacía o sin acceso", msg = "Selecciona una carpeta con 'SAF' para navegar árboles externos.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(entries) { entry -> EntryRow(entry) { currentPath = entry.path } }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FileEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = VSurface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${if (entry.isDirectory) "[DIR] " else ""}${entry.name}",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Text(entry.sizeHuman + " · " + entry.path.takeLast(50),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            if (entry.isDirectory) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onClick) { Text("Abrir") }
            }
        }
    }
}
