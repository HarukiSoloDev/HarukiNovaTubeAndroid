package com.harukisolodev.harukistream.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.BuildConfig
import com.harukisolodev.harukistream.core.HarukiConstants
import com.harukisolodev.harukistream.ui.components.premiumClickable
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun YouHubScreen(onEqualizer: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding(),
        contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 118.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("You", style = MaterialTheme.typography.headlineMedium, color = HarukiText, fontWeight = FontWeight.ExtraBold)
            Text("Your NovaTube controls and support.", color = HarukiMuted)
        }
        item {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HarukiPrimary.copy(alpha = .45f))
            ) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(Color(0xFF35141B), Color(0xFF18111D), Color(0xFF101722)))
                    ).padding(19.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(HarukiPrimary.copy(alpha = .16f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Favorite, null, tint = HarukiPrimary, modifier = Modifier.size(27.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Support NovaTube", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("Help keep development and improvements going.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HarukiConstants.DONATION_URL))) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HarukiPrimary),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Rounded.Favorite, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Donate / Support NovaTube", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { YouEntry(Icons.Rounded.GraphicEq, "Equalizer", "Popular presets and custom 5-band tuning", onEqualizer) }
        item { YouEntry(Icons.Rounded.Settings, "Settings", "Playback, quality, downloads and preferences", onSettings) }
        item { YouEntry(Icons.Rounded.Info, "About Haruki NovaTube", "Version ${BuildConfig.VERSION_NAME} • project information", onAbout) }
        item {
            Surface(color = HarukiCardSoft, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = HarukiSuccess)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("No ad SDK", color = HarukiText, fontWeight = FontWeight.SemiBold)
                        Text("NovaTube itself contains no advertising SDK or sponsored placements.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun YouEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().premiumClickable(onClick = onClick),
        color = HarukiCard,
        shape = RoundedCornerShape(21.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HarukiMuted)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = HarukiMuted)
        }
    }
}
