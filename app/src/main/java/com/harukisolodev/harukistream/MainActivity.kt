package com.harukisolodev.harukistream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.harukisolodev.harukistream.ui.HarukiApp
import com.harukisolodev.harukistream.player.PlaybackLaunch
import com.harukisolodev.harukistream.player.PlaybackService
import com.harukisolodev.harukistream.download.DownloadLaunch

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")
    private var openRequestId by mutableStateOf(0L)
    private var launchDestination by mutableStateOf("")
    private var navigationRequestId by mutableStateOf(0L)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        applyIncomingIntent(intent)
        setContent {
            HarukiApp(
                initialUrl = sharedUrl,
                openRequestId = openRequestId,
                launchDestination = launchDestination,
                navigationRequestId = navigationRequestId
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.notifyAppForeground(true)
    }

    override fun onStop() {
        PlaybackService.notifyAppForeground(false)
        super.onStop()
    }

    private fun applyIncomingIntent(intent: Intent?) {
        // A media-notification tap can arrive before onStart. Mark the already-running
        // playback service foreground first so its warm video renderer is ready before
        // Compose navigates back to the Watch PlayerView.
        if (intent?.action == PlaybackLaunch.ACTION_OPEN_NOW_PLAYING) {
            PlaybackService.notifyAppForeground(true)
        }
        when (intent?.action) {
            PlaybackLaunch.ACTION_OPEN_NOW_PLAYING -> {
                launchDestination = ""
                sharedUrl = intent.getStringExtra(PlaybackLaunch.EXTRA_VIDEO_URL).orEmpty().trim()
                if (sharedUrl.isNotBlank()) openRequestId++
            }
            DownloadLaunch.ACTION_OPEN_DOWNLOADS -> {
                // A download notification is a navigation request, not a media request.
                // Keep the current PlaybackService session alive while showing Downloads.
                sharedUrl = ""
                launchDestination = "DOWNLOADS"
                navigationRequestId++
            }
            else -> {
                launchDestination = ""
                sharedUrl = extractSharedText(intent)
                if (sharedUrl.isNotBlank()) openRequestId++
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return ""
        return intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
    }
}
