package com.harukisolodev.harukistream.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harukisolodev.harukistream.core.HarukiConstants
import com.harukisolodev.harukistream.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

@Composable
fun PageHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = HarukiText)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HarukiMuted)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HarukiText)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HarukiMuted)
        }
    }
}

@Composable
fun HarukiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = HarukiCard,
        contentColor = HarukiText,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun AccentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = HarukiText,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF36518F)),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(Color(0xFF15213A), Color(0xFF101827), Color(0xFF151C31))
                )
            )
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@Composable
fun StatusPill(text: String, positive: Boolean = true) {
    val tint = if (positive) HarukiSuccess else HarukiWarning
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Text(text, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ChoiceDropdown(
    label: String,
    selected: String,
    items: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = HarukiMuted2)
        Box {
            OutlinedButton(
                onClick = { if (items.isNotEmpty()) expanded = true },
                enabled = items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = HarukiCardSoft,
                    contentColor = HarukiText,
                    disabledContainerColor = HarukiCardSoft.copy(alpha = 0.55f),
                    disabledContentColor = HarukiMuted2
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (expanded) HarukiPrimary else HarukiBorder
                )
            ) {
                Text(
                    selected.ifBlank { "Paste a link first" },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected.isBlank()) HarukiMuted2 else HarukiText
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = HarukiMuted)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = HarukiCard2,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(14.dp)
            ) {
                items.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text,
                                color = HarukiText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(id)
                        }
                    )
                }
            }
        }
    }
}

private object RemoteImageLoader {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt().coerceAtLeast(16 * 1024)
    private val cache = object : LruCache<String, Bitmap>(maxKb / 16) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    @Volatile private var sharedClient: OkHttpClient? = null
    private val urlLocks = ConcurrentHashMap<String, Any>()

    private fun client(context: android.content.Context): OkHttpClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: OkHttpClient.Builder()
                .cache(Cache(java.io.File(context.applicationContext.cacheDir, "haruki_thumbnails"), 64L * 1024L * 1024L))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
                .also { sharedClient = it }
        }
    }

    fun cached(url: String): Bitmap? = synchronized(cache) { cache.get(url) }

    fun load(context: android.content.Context, url: String): Bitmap? {
        cached(url)?.let { return it }
        val lock = urlLocks.getOrPut(url) { Any() }
        return try {
            synchronized(lock) {
                cached(url)?.let { return@synchronized it }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", HarukiConstants.USER_AGENT)
                    .build()
                runCatching {
                    client(context).newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val bytes = response.body.bytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        // Feed/Shorts thumbnails do not need full 1080p decoded bitmaps. A
                        // 720px cap substantially cuts bitmap RAM and decode work while staying
                        // sharp on phone/tablet cards.
                        while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sample.coerceAtLeast(1)
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                }.getOrNull()?.also { bitmap -> synchronized(cache) { cache.put(url, bitmap) } }
            }
        } finally {
            urlLocks.remove(url, lock)
        }
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(18.dp)) {
    val context = LocalContext.current
    val cached = remember(url) { if (url.isBlank()) null else RemoteImageLoader.cached(url) }
    val bitmap by produceState<Bitmap?>(initialValue = cached, url) {
        if (value == null && url.isNotBlank()) {
            value = withContext(Dispatchers.IO) { RemoteImageLoader.load(context, url) }
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(HarukiSurface),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF141E35), Color(0xFF271C48)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("HARUKI", color = HarukiMuted, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "" else "${formatBytes(bytesPerSecond)}/s"


@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HarukiText,
    linkColor: Color = HarukiPrimary,
    textSizeSp: Float = 14f
) {
    val bodyColor = color.toArgb()
    val activeLinkColor = linkColor.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setTextIsSelectable(false)
                setPadding(0, 0, 0, 0)
            }
        },
        update = { view ->
            view.text = text
            view.setTextColor(bodyColor)
            view.setLinkTextColor(activeLinkColor)
            view.textSize = textSizeSp
            view.autoLinkMask = Linkify.WEB_URLS
            Linkify.addLinks(view, Linkify.WEB_URLS)
        }
    )
}
