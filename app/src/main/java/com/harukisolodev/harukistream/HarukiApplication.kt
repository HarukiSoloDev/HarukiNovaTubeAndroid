package com.harukisolodev.harukistream

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.harukisolodev.harukistream.core.HarukiConstants
import com.harukisolodev.harukistream.core.network.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class HarukiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader(), Localization("en", "MY"))
        createDownloadChannel()
    }

    private fun createDownloadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HarukiConstants.DOWNLOAD_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "NovaTube download progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
