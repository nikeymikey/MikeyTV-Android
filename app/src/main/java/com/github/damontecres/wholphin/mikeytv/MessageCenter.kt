package com.github.damontecres.wholphin.mikeytv

import android.content.Context
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** One message sent from the Jellyfin admin dashboard. */
@Serializable
data class AdminMessage(
    val id: String,
    val header: String,
    val text: String,
    val receivedAt: Long,
    val read: Boolean = false,
    val reply: String? = null,
)

@Serializable
data class ReplyStatus(
    @SerialName("enabled") val enabled: Boolean = false,
)

@Serializable
data class ReplyRequest(
    @SerialName("Text") val text: String,
    @SerialName("OriginalText") val originalText: String,
)

/**
 * Keeps admin messages, shows the newest as a toast that never takes focus, and
 * sends replies through the MikeyTV Messaging server plugin.
 *
 * The plugin is needed because Jellyfin has no reply channel: a normal user
 * cannot message another user's session.
 */
@Singleton
class MessageCenter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        @param:IoCoroutineScope private val scope: CoroutineScope,
    ) {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }

        private val _messages = MutableStateFlow(load())
        val messages: StateFlow<List<AdminMessage>> = _messages.asStateFlow()

        private val _toast = MutableStateFlow<AdminMessage?>(null)
        val toast: StateFlow<AdminMessage?> = _toast.asStateFlow()

        private val _panelOpen = MutableStateFlow(false)
        val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

        private val _replySupported = MutableStateFlow<Boolean?>(null)
        val replySupported: StateFlow<Boolean?> = _replySupported.asStateFlow()

        private var toastJob: Job? = null
        private var userKey: String? = prefs.getString(KEY_USER, null)

        /** Messages belong to the signed-in user, so a different user starts empty. */
        fun onUserChanged(key: String?) {
            if (key == userKey) return
            userKey = key
            prefs.edit().putString(KEY_USER, key).apply()
            _replySupported.value = null
            if (key != null) clear()
        }

        fun receive(
            header: String?,
            text: String?,
            requestedTimeoutMs: Long?,
        ) {
            val h = header.orEmpty()
            val t = text.orEmpty()
            if (h.isBlank() && t.isBlank()) return
            val message =
                AdminMessage(
                    id = UUID.randomUUID().toString(),
                    header = h,
                    text = t,
                    receivedAt = System.currentTimeMillis(),
                )
            _messages.update { (listOf(message) + it).take(MAX_STORED) }
            save()

            toastJob?.cancel()
            _toast.value = message
            toastJob =
                scope.launch {
                    delay(toastDurationMs(requestedTimeoutMs, t).milliseconds)
                    _toast.compareAndSet(message, null)
                }
            // Find out early, so the panel never waits when it opens.
            scope.launch { checkReplySupport() }
        }

        fun openPanel() {
            if (_messages.value.isEmpty()) return
            toastJob?.cancel()
            _toast.value = null
            _panelOpen.value = true
            markAllRead()
            scope.launch { checkReplySupport() }
        }

        fun closePanel() {
            _panelOpen.value = false
        }

        fun clear() {
            toastJob?.cancel()
            _toast.value = null
            _panelOpen.value = false
            _messages.value = emptyList()
            prefs.edit().remove(KEY_MESSAGES).apply()
        }

        /**
         * Only an explicit enabled:true counts, because a server or another plugin
         * can answer unknown paths with something else. Failures are not cached.
         */
        suspend fun checkReplySupport(): Boolean {
            if (_replySupported.value == true) return true
            val supported =
                try {
                    api.get<ReplyStatus>(STATUS_PATH).content.enabled
                } catch (ex: Exception) {
                    Timber.d(ex, "Reply plugin not available")
                    false
                }
            _replySupported.value = supported
            return supported
        }

        /** Throws if sending fails, so the panel can keep the text and offer another try. */
        suspend fun sendReply(
            messageId: String,
            text: String,
        ) {
            val reply = text.trim().take(MAX_REPLY_LENGTH)
            require(reply.isNotEmpty()) { "Reply is empty" }
            val original = _messages.value.firstOrNull { it.id == messageId }
            api.post<Unit>(
                REPLY_PATH,
                requestBody = ReplyRequest(text = reply, originalText = original?.text.orEmpty()),
            )
            _messages.update { list ->
                list.map { if (it.id == messageId) it.copy(reply = reply, read = true) else it }
            }
            save()
        }

        private fun markAllRead() {
            if (_messages.value.none { !it.read }) return
            _messages.update { list -> list.map { it.copy(read = true) } }
            save()
        }

        private fun load(): List<AdminMessage> =
            try {
                prefs
                    .getString(KEY_MESSAGES, null)
                    ?.let { json.decodeFromString<List<AdminMessage>>(it) }
                    .orEmpty()
                    .take(MAX_STORED)
            } catch (ex: Exception) {
                Timber.w(ex, "Could not read saved messages")
                emptyList()
            }

        private fun save() {
            prefs.edit().putString(KEY_MESSAGES, json.encodeToString(_messages.value)).apply()
        }

        companion object {
            const val MAX_STORED = 20
            const val MAX_REPLY_LENGTH = 200
            private const val PREFS_NAME = "mikeytv_messages"
            private const val KEY_MESSAGES = "messages"
            private const val KEY_USER = "user"
            private const val STATUS_PATH = "/MikeyTV/Messages/Status"
            private const val REPLY_PATH = "/MikeyTV/Messages/Reply"
            private const val MIN_TOAST_MS = 12_000L
            private const val MAX_TOAST_MS = 30_000L

            /**
             * The dashboard always asks for 5 seconds, too short to read and react
             * on a TV, so the request is only a minimum and long messages stay longer.
             */
            fun toastDurationMs(
                requestedMs: Long?,
                text: String,
            ): Long {
                val byLength = 6_000L + text.length * 60L
                return maxOf(MIN_TOAST_MS, requestedMs ?: 0L, byLength).coerceAtMost(MAX_TOAST_MS)
            }
        }
    }
