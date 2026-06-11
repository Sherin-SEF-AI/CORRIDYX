package ai.deepmost.corridyx.ui

import ai.deepmost.corridyx.ui.screens.CorridorMapScreen
import ai.deepmost.corridyx.ui.screens.DriveScreen
import ai.deepmost.corridyx.ui.screens.FleetNodeScreen
import ai.deepmost.corridyx.ui.screens.SessionsScreen
import ai.deepmost.corridyx.ui.screens.SettingsScreen
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.CorridyxTheme
import ai.deepmost.corridyx.ui.theme.TextDim
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CorridyxTheme {
                val vm: CorridyxViewModel = viewModel()
                PermissionGate {
                    AppScaffold(vm)
                }
            }
        }
    }
}

private val requiredPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ok = granted()
    }
    if (ok) { content(); return }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CORRIDYX", color = Accent)
        Text(
            "Corridor ODD certification needs the camera (corridor seeing), precise location " +
                "(segment + GNSS quality), and notifications (shift-long foreground capture). " +
                "No video is ever stored — only numeric scores and a few blurred evidence frames.",
            color = TextDim,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = { launcher.launch(requiredPermissions) }) { Text("GRANT PERMISSIONS") }
    }
}

private enum class Tab(val label: String) { DRIVE("DRIVE"), MAP("MAP"), SESSIONS("SESSIONS"), NODE("NODE"), SETTINGS("SETTINGS") }

@Composable
private fun AppScaffold(vm: CorridyxViewModel) {
    var tab by remember { mutableStateOf(Tab.DRIVE) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Bg) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(iconFor(t), contentDescription = t.label) },
                        label = { Text(t.label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = TextDim, unselectedTextColor = TextDim,
                            indicatorColor = Bg,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.DRIVE -> DriveScreen(vm)
                Tab.MAP -> CorridorMapScreen(vm)
                Tab.SESSIONS -> SessionsScreen(vm)
                Tab.NODE -> FleetNodeScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun iconFor(t: Tab) = when (t) {
    Tab.DRIVE -> Icons.Filled.Videocam
    Tab.MAP -> Icons.Filled.Map
    Tab.SESSIONS -> Icons.Filled.Dataset
    Tab.NODE -> Icons.Filled.Router
    Tab.SETTINGS -> Icons.Filled.Settings
}
