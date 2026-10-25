package com.pestpoint.radioauto.tvcam.config

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pestpoint.radioauto.tvcam.overlay.KeepAliveService

/** Not the primary configuration path (that's ConfigReceiver, driven by adb/Home Assistant setup)
 *  — this exists mainly so the app is visible/launchable at all on the TV home screen, so the HA
 *  URL/token can be entered or corrected by hand if adb access isn't handy, and so the "draw over
 *  other apps" permission state can be seen and opened from the TV itself. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Covers "just installed, TV won't reboot today" — BootReceiver handles every reboot
        // after this one.
        startForegroundService(Intent(this, KeepAliveService::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8AB4FF))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = remember { HaConfigPrefs(context) }
    var url by remember { mutableStateOf(prefs.haBaseUrl.orEmpty()) }
    var token by remember { mutableStateOf(prefs.haToken.orEmpty()) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text("Camera Overlay — Ρυθμίσεις", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Home Assistant URL (π.χ. http://192.168.1.10:8123)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Long-Lived Access Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Button(
            onClick = {
                prefs.haBaseUrl = url
                prefs.haToken = token
            },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Αποθήκευση")
        }

        Text(
            if (overlayGranted) "Άδεια εμφάνισης πάνω από άλλες εφαρμογές: ενεργή" else "Άδεια εμφάνισης πάνω από άλλες εφαρμογές: ΔΕΝ έχει δοθεί",
            modifier = Modifier.padding(top = 32.dp),
            color = if (overlayGranted) Color(0xFF3DDC84) else MaterialTheme.colorScheme.error,
        )
        if (!overlayGranted) {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }
                    overlayGranted = Settings.canDrawOverlays(context)
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Άνοιγμα ρυθμίσεων άδειας")
            }
        }
    }
}
