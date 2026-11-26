package com.neko.secretary.perception

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

data class SharedIntakePayload(
    val text: String?,
    val imageUri: Uri?,
    val mimeType: String? = null
)

data class ShareIntakeCardUi(
    val cardId: String,
    val kind: String,
    val title: String,
    val summary: String,
    val dueAt: Long? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val tags: List<String> = emptyList(),
    val localOnly: Boolean = false
)

data class HubEndpointConfig(
    val baseUrl: String = "",
    val bearerToken: String = ""
)

object HubEndpointStore {
    private const val FILE = "neko_hub_endpoint"
    private const val KEY_URL = "hub_base_url"
    private const val KEY_TOKEN = "hub_bearer_token"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): HubEndpointConfig {
        val p = prefs(context)
        return HubEndpointConfig(
            baseUrl = p.getString(KEY_URL, "").orEmpty(),
            bearerToken = p.getString(KEY_TOKEN, "").orEmpty()
        )
    }

    fun save(context: Context, config: HubEndpointConfig) {
        val normalizedUrl = config.baseUrl
            .trim()
            .replace('。', '.')
            .replace('：', ':')
            .replace('／', '/')
            .trimEnd('/')
        prefs(context).edit()
            .putString(KEY_URL, normalizedUrl)
            .putString(KEY_TOKEN, config.bearerToken.trim())
            .apply()
    }
}

/**
 * Pure protocol helpers for Share Sheet intake (JVM unit-testable without Android Context).
 */
object ShareIntakeCodec {
    fun computeIdempotencyKey(text: String?, imageBytes: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!text.isNullOrBlank()) digest.update(text.trim().toByteArray(Charsets.UTF_8))
        if (imageBytes != null && imageBytes.isNotEmpty()) digest.update(imageBytes)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return "android:share:$hex"
    }

    fun buildIntakeRequestJson(
        text: String?,
        imageBase64: String?,
        imageMimeType: String?,
        idempotencyKey: String,
        mode: AssistantMode,
        stealthOffice: Boolean
    ): String = JSONObject().apply {
        if (!text.isNullOrBlank()) put("text", text.trim())
        if (!imageBase64.isNullOrBlank()) {
            put("imageBase64", imageBase64)
            put("imageMimeType", imageMimeType ?: "image/jpeg")
        }
        put("idempotencyKey", idempotencyKey)
        put("mode", mode.wireName)
        put("stealthOffice", stealthOffice)
    }.toString()

    fun parseCardFromResponse(responseJson: String): ShareIntakeCardUi? = runCatching {
        val root = JSONObject(responseJson)
        val card = root.optJSONObject("card") ?: root
        val cardId = card.optString("cardId", "")
        if (cardId.isBlank()) return@runCatching null
        val tagsArr = card.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i, "")
                if (t.isNotBlank()) add(t)
            }
        }
        ShareIntakeCardUi(
            cardId = cardId,
            kind = card.optString("kind", "note"),
            title = card.optString("title", "待确认卡片"),
            summary = card.optString("summary", ""),
            dueAt = if (card.has("dueAt") && !card.isNull("dueAt")) card.optLong("dueAt") else null,
            amount = if (card.has("amount") && !card.isNull("amount")) card.optDouble("amount") else null,
            currency = card.optString("currency", "").ifBlank { null },
            tags = tags,
            localOnly = false
        )
    }.getOrNull()

    fun buildOfflineFallbackCard(text: String?, hasImage: Boolean): ShareIntakeCardUi {
        val clean = text?.trim().orEmpty()
        val kind = when {
            Regex("(?:¥|￥|RMB|\\$|元)").containsMatchIn(clean) -> "bill"
            Regex("(?:截止|DDL|笔试|面试|考试|deadline)", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "deadline"
            Regex("(?:会议|日程|预约|明天|meeting)", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "schedule"
            else -> if (hasImage) "note" else "inspiration"
        }
        return ShareIntakeCardUi(
            cardId = "local-${UUID.randomUUID()}",
            kind = kind,
            title = clean.take(28).ifBlank { if (hasImage) "截图速记（离线预览）" else "分享内容" },
            summary = clean.ifBlank { "已接收截图，配置 Core Hub 地址后将自动调用 Vision 解析" },
            tags = listOf(kind, if (hasImage) "screenshot" else "share"),
            localOnly = true
        )
    }
}

object ShareIntakeHandler {
    private const val TAG = "ShareIntakeHandler"
    private const val MAX_IMAGE_DIM = 1280
    private const val JPEG_QUALITY = 82

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingCard = MutableStateFlow<ShareIntakeCardUi?>(null)
    val pendingCard: StateFlow<ShareIntakeCardUi?> = _pendingCard.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun extractSharedPayload(intent: Intent): SharedIntakePayload {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return SharedIntakePayload(text = sharedText, imageUri = imageUri, mimeType = intent.type)
    }

    fun ingestIntent(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val payload = extractSharedPayload(intent)
        if (payload.text.isNullOrBlank() && payload.imageUri == null) return

        _uploading.value = true
        _statusMessage.value = "正在解析分享内容…"

        scope.launch {
            try {
                val imageBytes = payload.imageUri?.let { readAndCompressImage(appContext, it) }
                val imageBase64 = imageBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                val hub = HubEndpointStore.load(appContext)

                val card = if (hub.baseUrl.isNotBlank()) {
                    uploadToHub(
                        hub = hub,
                        text = payload.text,
                        imageBase64 = imageBase64,
                        imageBytes = imageBytes,
                        mimeType = if (imageBytes != null) "image/jpeg" else payload.mimeType
                    )
                } else {
                    ShareIntakeCodec.buildOfflineFallbackCard(payload.text, hasImage = imageBytes != null)
                }

                _pendingCard.value = card
                _statusMessage.value = if (card.localOnly) {
                    "离线预览卡片（配置 Hub 地址后可云端 Vision 解析并入库）"
                } else {
                    "解析完成，点击“一键入库”保存"
                }

                // Pop companion bubble immediately so the pet shows the parsed card even over other apps
                CharacterStateMachine.applyDirective(
                    CharacterDirective(
                        directiveId = "share-${card.cardId}",
                        mode = CharacterStateMachine.mode.value,
                        state = if (card.kind == "deadline") CharacterStateId.SECRETARY_ALERT else CharacterStateId.LIFE_GREETING,
                        stealthOverride = CharacterStateMachine.stealthOffice.value,
                        text = "[${card.kind}] ${card.title}（打开控制台一键入库）",
                        expression = "happy",
                        motion = "TapBody",
                        costume = if (CharacterStateMachine.mode.value == AssistantMode.SECRETARY) "secretary" else "default",
                        ttsAllowed = false,
                        ttsCacheKey = null
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Share intake failed", t)
                val fallback = ShareIntakeCodec.buildOfflineFallbackCard(payload.text, hasImage = payload.imageUri != null)
                _pendingCard.value = fallback
                _statusMessage.value = "Hub 连接失败（${t.message}），已生成本地预览"
            } finally {
                _uploading.value = false
            }
        }
    }

    fun confirmPending(context: Context) {
        val card = _pendingCard.value ?: return
        val hub = HubEndpointStore.load(context.applicationContext)
        if (card.localOnly || hub.baseUrl.isBlank()) {
            _pendingCard.value = null
            _statusMessage.value = "已本地确认：${card.title}"
            return
        }
        scope.launch {
            runCatching {
                val client = HttpClient(OkHttp)
                try {
                    val body = JSONObject().put("cardId", card.cardId).toString()
                    val resp = client.post("${hub.baseUrl}/api/share/confirm") {
                        contentType(ContentType.Application.Json)
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                        setBody(body)
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                } finally {
                    client.close()
                }
            }.onSuccess {
                _pendingCard.value = null
                _statusMessage.value = "已一键入库：${card.title}"
            }.onFailure { err ->
                _statusMessage.value = "入库失败：${err.message}"
            }
        }
    }

    fun dismissPending(context: Context) {
        val card = _pendingCard.value ?: return
        _pendingCard.value = null
        _statusMessage.value = "已忽略卡片"
        val hub = HubEndpointStore.load(context.applicationContext)
        if (!card.localOnly && hub.baseUrl.isNotBlank()) {
            scope.launch {
                runCatching {
                    val client = HttpClient(OkHttp)
                    try {
                        client.post("${hub.baseUrl}/api/share/dismiss") {
                            contentType(ContentType.Application.Json)
                            if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                            setBody(JSONObject().put("cardId", card.cardId).toString())
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }

    private suspend fun uploadToHub(
        hub: HubEndpointConfig,
        text: String?,
        imageBase64: String?,
        imageBytes: ByteArray?,
        mimeType: String?
    ): ShareIntakeCardUi {
        val key = ShareIntakeCodec.computeIdempotencyKey(text, imageBytes)
        val reqJson = ShareIntakeCodec.buildIntakeRequestJson(
            text = text,
            imageBase64 = imageBase64,
            imageMimeType = mimeType,
            idempotencyKey = key,
            mode = CharacterStateMachine.mode.value,
            stealthOffice = CharacterStateMachine.stealthOffice.value
        )
        val client = HttpClient(OkHttp)
        try {
            val resp = client.post("${hub.baseUrl}/api/share/intake") {
                contentType(ContentType.Application.Json)
                if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                setBody(reqJson)
            }
            val textBody = resp.bodyAsText()
            check(resp.status.isSuccess()) { "HTTP ${resp.status.value}: $textBody" }
            return ShareIntakeCodec.parseCardFromResponse(textBody)
                ?: error("Core Hub returned invalid share card payload")
        } finally {
            client.close()
        }
    }

    private fun readAndCompressImage(context: Context, uri: Uri): ByteArray? {
        val rawStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = rawStream.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = downscaleIfNeeded(bitmap, MAX_IMAGE_DIM)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}

/**
 * Pure P3 NotificationListener filter (zero APK reverse engineering).
 * Only forwards high-signal exam/interview/assessment, delivery, and AI task notifications.
 */
object ExternalNotificationFilter {
    private val INTERVIEW_REGEX = Regex("(?:面试|笔试|测评|机试|考试|腾讯会议|飞书会议|牛客|Offer|interview|exam)", RegexOption.IGNORE_CASE)
    private val DELIVERY_REGEX = Regex("(?:快递|取件码|菜鸟|丰巢|顺丰|驿站|派送中)", RegexOption.IGNORE_CASE)
    private val AI_TASK_REGEX = Regex("(?:构建完成|训练完成|build failed|error|completed|approval)", RegexOption.IGNORE_CASE)

    fun classify(packageName: String, title: String, text: String): String? {
        if (packageName == "com.neko.secretary") return null
        val combined = "$title $text".trim()
        if (combined.length < 3) return null
        return when {
            INTERVIEW_REGEX.containsMatchIn(combined) -> "interview_exam"
            DELIVERY_REGEX.containsMatchIn(combined) -> "delivery"
            AI_TASK_REGEX.containsMatchIn(combined) -> "ai_task"
            else -> null
        }
    }

    fun buildNotificationEventJson(
        packageName: String,
        title: String,
        text: String,
        category: String,
        postedAt: Long,
        eventId: String = UUID.randomUUID().toString()
    ): String {
        val key = ShareIntakeCodec.computeIdempotencyKey("$packageName|$title|$text", null)
            .replace("android:share:", "android:notif:")
        return JSONObject()
            .put("id", eventId)
            .put("idempotencyKey", key)
            .put("type", "sensor.notification_external")
            .put("source", "android")
            .put("ts", postedAt)
            .put(
                "payload",
                JSONObject()
                    .put("packageName", packageName)
                    .put("title", title)
                    .put("text", text)
                    .put("category", category)
            )
            .toString()
    }
}

class ExternalNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val category = ExternalNotificationFilter.classify(pkg, title, text) ?: return
        val eventJson = ExternalNotificationFilter.buildNotificationEventJson(
            packageName = pkg,
            title = title,
            text = text,
            category = category,
            postedAt = sbn.postTime
        )
        com.neko.secretary.sync.EventSyncCoordinator.sendEventAsync(applicationContext, eventJson)
    }
}
