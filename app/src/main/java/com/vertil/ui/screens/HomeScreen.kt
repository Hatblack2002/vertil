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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vertil.model.ModelState
import com.vertil.ui.VertilViewModel
import com.vertil.ui.theme.VBg
import com.vertil.ui.theme.VBgGradientTop
import com.vertil.ui.theme.VPrimary
import com.vertil.ui.theme.VSecondary
import com.vertil.ui.theme.VSurface

@Composable
fun HomeScreen(vm: VertilViewModel, onTalk: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val home by vm.home.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VBgGradientTop, VBg)))
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("VERTIL", color = VPrimary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text("Entorno local inteligente", color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))

        // Modelo activo
        StatusCard(
            title = "Modelo",
            value = home.activeModel?.name ?: "Sin modelo",
            subtitle = when (home.modelState) {
                ModelState.READY -> "● Local · Listo"
                ModelState.LOADING -> "● Cargando…"
                ModelState.GENERATING -> "● Generando…"
                ModelState.ERROR -> "● Error"
                else -> "○ No cargado"
            },
            subtitleColor = if (home.modelState == ModelState.READY) VPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // RAM
        StatusCard(
            title = "RAM",
            value = "${home.ramAvailableMb} MB",
            subtitle = "disponibles de ${home.ramTotalMb} MB",
            progress = if (home.ramTotalMb > 0) home.ramAvailableMb.toFloat() / home.ramTotalMb else 0f
        )
        Spacer(Modifier.height(12.dp))

        // Grid stats
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBox("Tareas", home.taskCount.toString(), Modifier.weight(1f))
            StatBox("Permisos", home.grantedPermissionCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBox("Herramientas", home.toolCount.toString(), Modifier.weight(1f))
            StatBox("Actividad", home.activityCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onTalk,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VPrimary, contentColor = Color.Black)
        ) {
            Text("Hablar con VERTIL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, subtitle: String, subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, progress: Float? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(value, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = subtitleColor, style = MaterialTheme.typography.bodySmall)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                    color = VPrimary, trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(value, color = VPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}
