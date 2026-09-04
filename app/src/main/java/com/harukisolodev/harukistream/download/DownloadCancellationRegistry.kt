package com.harukisolodev.harukistream.download

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local fast cancellation path for active OkHttp calls.
 * QueueStore remains the durable source of truth so cancellation also survives process restarts.
 */
object DownloadCancellationRegistry {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()

    fun clear(queueId: String) {
        if (queueId.isNotBlank()) cancelled.remove(queueId)
    }

    fun isCancelled(queueId: String): Boolean = queueId.isNotBlank() && queueId in cancelled

    fun register(queueId: String, call: Call) {
        if (queueId.isBlank()) return
        if (isCancelled(queueId)) {
            call.cancel()
            return
        }
        calls.compute(queueId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet<Call>()).apply { add(call) }
        }
    }

    fun unregister(queueId: String, call: Call) {
        if (queueId.isBlank()) return
        calls[queueId]?.let { set ->
            set.remove(call)
            if (set.isEmpty()) calls.remove(queueId, set)
        }
    }

    fun cancel(queueId: String) {
        if (queueId.isBlank()) return
        cancelled.add(queueId)
        calls.remove(queueId)?.toList()?.forEach { runCatching { it.cancel() } }
    }
}
