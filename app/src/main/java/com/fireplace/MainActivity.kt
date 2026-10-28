package com.fireplace

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fireplace.ui.ChatScreen
import com.fireplace.ui.Onboarding
import com.fireplace.ui.theme.FireplaceTheme

class MainActivity : ComponentActivity() {

    // Lets us show the download-progress notification on Android 13+. Denial is
    // harmless: the foreground service still keeps the download alive, just silently.
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fireplace is a dark-first brand (the glowing-hearth look), so the system bars
        // stay transparent with light icons regardless of the device's light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        maybeAskNotifications()
        setContent {
            FireplaceTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: ChatViewModel = viewModel()
                    ChatScreen(vm)
                    if (vm.showOnboarding) Onboarding(onDone = { vm.completeOnboarding() })
                }
            }
        }
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
