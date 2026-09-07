package com.gengyixiong.codexmeter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("meter", MODE_PRIVATE) }
    private val credentials by lazy { CredentialStore(this) }
    private var usage: Usage? = null
    private var online = false

    private lateinit var plan: TextView
    private lateinit var fivePercent: TextView
    private lateinit var fiveProgress: ProgressBar
    private lateinit var fiveRemaining: TextView
    private lateinit var fiveReset: TextView
    private lateinit var weekPercent: TextView
    private lateinit var weekProgress: ProgressBar
    private lateinit var weekRemaining: TextView
    private lateinit var weekReset: TextView
    private lateinit var status: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }
    private val refresher = object : Runnable {
        override fun run() {
            fetchUsage()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        runCatching {
            addContentView(
                CatOverlayView(this),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        immersive()
        credentials.import(File(filesDir, "auth.json"))
        checkParser()

        findViewById<View>(R.id.root).post {
            val width = findViewById<View>(R.id.root).width
            findViewById<View>(R.id.root).setPadding(width * 4 / 100, 0, width * 4 / 100, 0)
            listOf(fivePercent, weekPercent).forEach {
                it.setTextSize(TypedValue.COMPLEX_UNIT_PX, width * 0.085f)
            }
        }

        prefs.getString("cache", null)?.let { cached ->
            runCatching { parseUsage(cached) }.onSuccess { usage = it }
        }
        handler.post(ticker)
        handler.post(refresher)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        plan = findViewById(R.id.plan)
        fivePercent = findViewById(R.id.fivePercent)
        fiveProgress = findViewById(R.id.fiveProgress)
        fiveRemaining = findViewById(R.id.fiveRemaining)
        fiveReset = findViewById(R.id.fiveReset)
        weekPercent = findViewById(R.id.weekPercent)
        weekProgress = findViewById(R.id.weekProgress)
        weekRemaining = findViewById(R.id.weekRemaining)
        weekReset = findViewById(R.id.weekReset)
        status = findViewById(R.id.status)
    }

    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun fetchUsage() {
        executor.execute {
            runCatching {
                var auth = credentials.load() ?: error("Codex login required")
                try {
                    requestUsage(auth)
                } catch (_: Unauthorized) {
                    auth = refresh(auth)
                    requestUsage(auth)
                }
            }.onSuccess { json ->
                runCatching { parseUsage(json) }.onSuccess { parsed ->
                    prefs.edit().putString("cache", json).apply()
                    runOnUiThread {
                        usage = parsed
                        online = true
                        render()
                    }
                }.onFailure { markOffline() }
            }.onFailure { markOffline() }
        }
    }

    private fun requestUsage(auth: AuthTokens): String {
        val connection = URL(USAGE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        connection.setRequestProperty("ChatGPT-Account-ID", auth.accountId)
        connection.setRequestProperty("User-Agent", "codex-meter/2.0")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw Unauthorized()
            if (code != HttpURLConnection.HTTP_OK) error("Usage request failed: $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun refresh(auth: AuthTokens): AuthTokens {
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", auth.refreshToken)
            .toString()
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("Login refresh failed: $code")
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val updated = AuthTokens(
                response.getString("access_token"),
                response.getString("refresh_token"),
                auth.accountId,
            )
            credentials.save(updated)
            return updated
        } finally {
            connection.disconnect()
        }
    }

    private fun markOffline() = runOnUiThread {
        online = false
        render()
    }

    private fun render() {
        val current = usage
        plan.text = current?.plan?.uppercase(Locale.US) ?: "—"
        renderWindow(current?.fiveHour, fivePercent, fiveProgress, fiveRemaining, fiveReset)
        renderWindow(current?.weekly, weekPercent, weekProgress, weekRemaining, weekReset)

        val time = current?.updatedAt?.let {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1_000))
        } ?: "—"
        if (online) {
            status.text = "● DIRECT   •   UPDATED $time   •   AUTO 60S"
            status.setTextColor(GREEN)
        } else {
            status.text = "● OFFLINE   •   LAST $time   •   AUTO 60S"
            status.setTextColor(MUTED)
        }
    }

    private fun renderWindow(
        window: UsageWindow?,
        percent: TextView,
        progress: ProgressBar,
        remaining: TextView,
        reset: TextView,
    ) {
        if (window == null || !window.available) {
            percent.text = "—"
            progress.progress = 0
            remaining.text = "NOT REPORTED"
            reset.text = "Waiting for Codex"
            percent.setTextColor(TEXT)
            progress.progressTintList = ColorStateList.valueOf(GREEN)
            progress.progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            return
        }

        val used = window.usedPercent.roundToInt().coerceIn(0, 100)
        val color = when {
            used >= 90 -> RED
            used >= 75 -> YELLOW
            else -> GREEN
        }
        percent.text = "$used%"
        percent.setTextColor(color)
        progress.progress = used
        progress.progressTintList = ColorStateList.valueOf(color)
        progress.progressBackgroundTintList = ColorStateList.valueOf(TRACK)
        remaining.text = "${100 - used}% REMAINING"
        reset.text = countdown(window.resetsAt)
    }

    private fun countdown(resetAt: Long): String {
        if (resetAt <= 0) return "Reset time unavailable"
        var seconds = (resetAt - System.currentTimeMillis() / 1_000).coerceAtLeast(0)
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        seconds %= 60
        return if (days > 0) "Resets in ${days}d ${hours}h ${minutes}m" else
            "Resets in ${hours}h ${minutes}m ${seconds}s"
    }

    private fun parseUsage(json: String): Usage {
        val root = JSONObject(json)
        val direct = root.optJSONObject("rate_limit")
        fun window(name: String, legacyName: String): UsageWindow {
            val value = direct?.optJSONObject(name) ?: root.optJSONObject(legacyName)
                ?: return UsageWindow(false, 0.0, 0)
            return UsageWindow(
                direct != null || value.optBoolean("available"),
                value.optDouble(if (direct != null) "used_percent" else "usedPercent", 0.0),
                value.optLong(if (direct != null) "reset_at" else "resetsAt", 0),
            )
        }
        return Usage(
            root.optString(if (direct != null) "plan_type" else "plan", "unknown"),
            root.optLong("updatedAt", System.currentTimeMillis() / 1_000),
            window("primary_window", "fiveHour"),
            window("secondary_window", "weekly"),
        )
    }

    private fun checkParser() {
        val parsed = parseUsage(
            """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":12.5,"reset_at":42},"secondary_window":{"used_percent":25,"reset_at":84}}}""",
        )
        check(parsed.plan == "plus" && parsed.fiveHour.usedPercent == 12.5 && parsed.weekly.resetsAt == 84L)
    }

    companion object {
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private val TEXT = Color.parseColor("#C7C49B")
        private val MUTED = Color.parseColor("#7F836A")
        private val TRACK = Color.parseColor("#313B30")
        private val GREEN = Color.parseColor("#379C83")
        private val YELLOW = Color.parseColor("#C49A4A")
        private val RED = Color.parseColor("#D54A3E")
    }
}

private class Unauthorized : Exception()

private data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
)

private class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun import(file: File) {
        if (!file.isFile) return
        val json = runCatching { JSONObject(file.readText()) }
            .getOrElse {
                file.delete()
                throw it
            }
        save(
            AuthTokens(
                json.getString("access_token"),
                json.getString("refresh_token"),
                json.getString("account_id"),
            ),
        )
        file.delete()
    }

    fun load(): AuthTokens? = runCatching {
        val blob = prefs.getString("tokens", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        val json = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8))
        AuthTokens(
            json.getString("access_token"),
            json.getString("refresh_token"),
            json.getString("account_id"),
        )
    }.getOrNull()

    fun save(tokens: AuthTokens) {
        val json = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
            .put("account_id", tokens.accountId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(
            prefs.edit().putString(
                "tokens",
                Base64.encodeToString(cipher.iv + cipher.doFinal(json), Base64.NO_WRAP),
            ).commit(),
        ) { "Could not persist refreshed Codex login" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "codex-meter-auth"
    }
}

data class Usage(
    val plan: String,
    val updatedAt: Long,
    val fiveHour: UsageWindow,
    val weekly: UsageWindow,
)

data class UsageWindow(
    val available: Boolean,
    val usedPercent: Double,
    val resetsAt: Long,
)

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
