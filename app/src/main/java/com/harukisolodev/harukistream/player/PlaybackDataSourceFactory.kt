package com.harukisolodev.harukistream.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.harukisolodev.harukistream.core.HarukiConstants
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared playback networking for long videos and Shorts.
 *
 * v0.8.0 deliberately uses OkHttp for media instead of Android's basic
 * HttpURLConnection stack. OkHttp gives Google/CDN endpoints HTTP/2 connection reuse
 * where supported, while the same disk cache prevents re-fetching already-read ranges.
 */
@OptIn(UnstableApi::class)
object PlaybackDataSourceFactory {
    private const val PLAYBACK_CACHE_BYTES = 384L * 1024L * 1024L

    @Volatile private var cacheInstance: SimpleCache? = null

    private val mediaHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            // Keep enough room for a video stream + separate audio + subtitles and a
            // small next-item warmup without serializing everything onto one socket.
            maxRequests = 24
            maxRequestsPerHost = 10
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun cache(context: Context): SimpleCache {
        cacheInstance?.let { return it }
        return synchronized(this) {
            cacheInstance ?: run {
                val app = context.applicationContext
                val provider = StandaloneDatabaseProvider(app)
                SimpleCache(
                    File(app.cacheDir, "novatube_playback_cache"),
                    LeastRecentlyUsedCacheEvictor(PLAYBACK_CACHE_BYTES),
                    provider
                ).also { cacheInstance = it }
            }
        }
    }

    fun create(
        context: Context,
        transferListener: TransferListener? = null,
        headers: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        // identity is important for byte ranges/progressive MP4 and avoids a proxy/CDN
        // unexpectedly gzip-transforming a response that ExoPlayer expects to seek.
        val requestHeaders = headers + mapOf(
            "Accept" to (headers["Accept"] ?: "*/*"),
            "Accept-Encoding" to (headers["Accept-Encoding"] ?: "identity")
        )
        val http = OkHttpDataSource.Factory(mediaHttpClient)
            .setUserAgent(headers["User-Agent"] ?: HarukiConstants.USER_AGENT)
            .setDefaultRequestProperties(requestHeaders)
        if (transferListener != null) http.setTransferListener(transferListener)

        val upstream = DefaultDataSource.Factory(context.applicationContext, http)

        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
