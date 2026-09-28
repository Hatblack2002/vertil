package com.vertil.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vertil.di.ServiceLocator
import com.vertil.ui.screens.ActivityScreen
import com.vertil.ui.screens.AutomationsScreen
import com.vertil.ui.screens.ChatScreen
import com.vertil.ui.screens.FilesScreen
import com.vertil.ui.screens.HomeScreen
import com.vertil.ui.screens.ModelsScreen
import com.vertil.ui.screens.PermissionsScreen
import com.vertil.ui.screens.SettingsScreen
import com.vertil.ui.theme.VertilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!ServiceLocator.isInitialized()) ServiceLocator.init(applicationContext)
        enableEdgeToEdge()
        setContent { VertilTheme { RootScreen() } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Filled.AutoAwesome),
    CHAT("Chat", Icons.Filled.Build),
    MODELS("Modelos", Icons.Filled.ModelTraining),
    FILES("Archivos", Icons.Filled.Folder),
    ACTIVITY("Actividad", Icons.Filled.History),
    AUTOMATIONS("Auto", Icons.Filled.Settings),
    PERMISSIONS("Permisos", Icons.Filled.Security),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@Composable
private fun RootScreen(vm: VertilViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val snackbarHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    // Permisos mínimos (especificación §12): SAF (ACTION_OPEN_DOCUMENT) NO
    // necesita READ_MEDIA_* ni READ_EXTERNAL_STORAGE. Solo se solicita
    // POST_NOTIFICATIONS (notificaciones de monitoreo en Android 13+).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultado: la app degrada gracefully si no se concede */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(arrayOf(perm))
            }
        }
    }

    LaunchedEffect(vm.snackbar) {
        vm.snackbar.value?.let {
            snackbarHost.showSnackbar(it); vm.consumeSnack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val tabs = listOf(Tab.HOME, Tab.CHAT, Tab.MODELS, Tab.FILES, Tab.ACTIVITY, Tab.AUTOMATIONS, Tab.PERMISSIONS, Tab.SETTINGS)
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = itemColors
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(vm, onTalk = { tab = Tab.CHAT }, contentPadding = padding)
                    Tab.CHAT -> ChatScreen(vm, contentPadding = padding)
                    Tab.MODELS -> ModelsScreen(vm, contentPadding = padding)
                    Tab.FILES -> FilesScreen(vm, contentPadding = padding)
                    Tab.ACTIVITY -> ActivityScreen(vm, contentPadding = padding)
                    Tab.AUTOMATIONS -> AutomationsScreen(vm, contentPadding = padding)
                    Tab.PERMISSIONS -> PermissionsScreen(vm, contentPadding = padding)
                    Tab.SETTINGS -> SettingsScreen(vm, contentPadding = padding)
                }
            }
        }
    }
}
