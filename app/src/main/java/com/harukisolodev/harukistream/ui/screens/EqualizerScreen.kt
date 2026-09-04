package com.harukisolodev.harukistream.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.AppSettings
import com.harukisolodev.harukistream.data.EqualizerPreset
import com.harukisolodev.harukistream.player.PlaybackService
import com.harukisolodev.harukistream.ui.HarukiViewModel
import com.harukisolodev.harukistream.ui.theme.*
import java.util.Locale

@Composable
fun EqualizerScreen(vm: HarukiViewModel, settings: AppSettings, onBack: () -> Unit) {
    val selectedCurve = if (settings.equalizerPreset == EqualizerPreset.CUSTOM) {
        settings.equalizerCustomBands
    } else {
        settings.equalizerPreset.bandsDb
    }
    var tuningCurve by remember { mutableStateOf(selectedCurve) }

    LaunchedEffect(settings.equalizerPreset, settings.equalizerCustomBands) {
        tuningCurve = selectedCurve
    }

    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        HeaderRow("Equalizer", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, HarukiPrimary.copy(alpha = .38f))
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF131D2B), Color(0xFF181322), Color(0xFF10151D))
                                )
                            )
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(HarukiPrimary.copy(alpha = .15f), RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.GraphicEq, null, tint = HarukiPrimary, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Nova Equalizer", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    if (settings.equalizerEnabled) "${settings.equalizerPreset.displayName} is active" else "Off • original audio",
                                    color = if (settings.equalizerEnabled) HarukiPrimary else HarukiMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = settings.equalizerEnabled,
                                onCheckedChange = vm::setEqualizerEnabled,
                                colors = SwitchDefaults.colors(checkedTrackColor = HarukiPrimary, checkedThumbColor = Color.White)
                            )
                        }
                        EqualizerMiniGraph(tuningCurve, enabled = settings.equalizerEnabled)
                    }
                }
            }

            item {
                Text("Popular presets", color = HarukiText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Quick setups for the sound people commonly want.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(EqualizerPreset.popular, key = { it.name }) { preset ->
                        val selected = settings.equalizerEnabled && settings.equalizerPreset == preset
                        FilterChip(
                            selected = selected,
                            onClick = {
                                tuningCurve = preset.bandsDb
                                vm.setEqualizerPreset(preset)
                                PlaybackService.previewEqualizer(true, preset.bandsDb)
                            },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.Headphones, null, modifier = Modifier.size(17.dp)) }
                            } else null,
                            label = { Text(preset.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HarukiPrimary,
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                                containerColor = HarukiCard,
                                labelColor = HarukiText
                            )
                        )
                    }
                }
            }

            item {
                Surface(
                    color = HarukiCard,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, HarukiBorderSoft)
                ) {
                    Column(
                        Modifier.fillMaxWidth().animateContentSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Tune, null, tint = HarukiViolet)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("Custom 5-band tuning", color = HarukiText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Move a band to create your own preset. Changes preview live while a normal video is playing.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        EQ_LABELS.forEachIndexed { index, label ->
                            EqualizerBandRow(
                                label = label,
                                gainDb = tuningCurve.getOrElse(index) { 0f },
                                onValueChange = { value ->
                                    val updated = tuningCurve.toMutableList().also { it[index] = value }
                                    tuningCurve = updated
                                    PlaybackService.previewEqualizer(true, updated)
                                },
                                onValueChangeFinished = {
                                    vm.setEqualizerCustomBands(tuningCurve)
                                }
                            )
                        }
                    }
                }
            }

            item {
                Surface(color = HarukiCardSoft, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, HarukiBorderSoft)) {
                    Text(
                        "NovaTube maps these five controls across the EQ bands provided by your phone. The exact hardware band count and maximum gain can vary by Android device, so NovaTube safely clamps the curve to what the device supports.",
                        color = HarukiMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EqualizerBandRow(
    label: String,
    gainDb: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = HarukiText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
            Slider(
                value = gainDb,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = -10f..10f,
                steps = 39,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = HarukiPrimary,
                    activeTrackColor = HarukiPrimary,
                    inactiveTrackColor = HarukiBorder
                )
            )
            Text(
                String.format(Locale.US, "%+.1f", gainDb),
                color = if (gainDb == 0f) HarukiMuted else HarukiPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(48.dp)
            )
        }
    }
}

@Composable
private fun EqualizerMiniGraph(curve: List<Float>, enabled: Boolean) {
    val display = if (enabled) curve else List(5) { 0f }
    Row(
        Modifier.fillMaxWidth().height(62.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        display.forEach { gain ->
            val normalized = ((gain + 10f) / 20f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(HarukiCard2, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(.18f + normalized * .72f)
                        .padding(3.dp)
                        .background(
                            if (enabled) Brush.verticalGradient(listOf(HarukiViolet, HarukiPrimary))
                            else Brush.verticalGradient(listOf(HarukiMuted2, HarukiMuted2)),
                            RoundedCornerShape(6.dp)
                        )
                )
            }
        }
    }
}

private val EQ_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
