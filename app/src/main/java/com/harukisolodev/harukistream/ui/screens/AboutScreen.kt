package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.BuildConfig
import com.harukisolodev.harukistream.R
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun AboutScreen(onMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        HeaderRow("About", onMenu)
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
            ) {
                Column(
                    Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(HarukiCard, HarukiPrimary.copy(alpha = .10f), HarukiCard))).padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Image(painterResource(R.drawable.haruki_logo), null, Modifier.size(86.dp).clip(RoundedCornerShape(22.dp)))
                    Text("HARUKI NOVATUBE", style = MaterialTheme.typography.headlineMedium, color = HarukiText, fontWeight = FontWeight.ExtraBold)
                    Text("A fast, focused YouTube client with native playback, Shorts, downloads and an ad-free Haruki interface.", color = HarukiMuted)
                    Text("Created & Developed by harukisolodev", color = HarukiPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Android v${BuildConfig.VERSION_NAME}", color = HarukiMuted)
                }
            }

            Surface(color = HarukiCard, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AboutLine(Icons.Rounded.Shield, "No ad SDK", "Haruki NovaTube itself contains no advertising SDK or sponsored placements.")
                    AboutLine(Icons.Rounded.Download, "Direct downloads", "Download supported YouTube videos directly from the watch screen.")
                    AboutLine(Icons.Rounded.PlayCircle, "Background player", "Media3 playback service provides Android media controls.")
                    AboutLine(Icons.Rounded.Info, "Personal project", "This build is designed for personal use. YouTube can change its delivery and page formats, so extractor updates may occasionally be required.")
                }
            }
        }
    }
}

@Composable
private fun AboutLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = HarukiPrimary)
        Column(Modifier.weight(1f)) {
            Text(title, color = HarukiText, fontWeight = FontWeight.SemiBold)
            Text(body, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
