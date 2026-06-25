package com.rssai.push.data

import android.content.Context

enum class BackendMode(val raw: String) {
    TERMUX("termux"),
    PC("pc");

    companion object {
        fun from(raw: String?): BackendMode =
            entries.firstOrNull { it.raw == raw } ?: TERMUX
    }
}

data class BackendProfile(
    val mode: BackendMode = BackendMode.TERMUX,
    val pcBaseUrl: String = "",
    val authToken: String = ""
) {
    val activeBaseUrl: String
        get() = when (mode) {
            BackendMode.TERMUX -> BackendSettings.TERMUX_BASE_URL
            BackendMode.PC -> BackendSettings.normalizeBaseUrl(pcBaseUrl)
                .ifBlank { BackendSettings.TERMUX_BASE_URL }
        }

    val activeAuthToken: String
        get() = if (mode == BackendMode.PC) authToken.trim() else ""
}

object BackendSettings {
    const val TERMUX_BASE_URL = "http://127.0.0.1:5000"

    private const val PREFS = "backend_settings"
    private const val KEY_MODE = "backendMode"
    private const val KEY_PC_BASE_URL = "backendBaseUrl"
    private const val KEY_AUTH_TOKEN = "authToken"

    fun normalizeBaseUrl(value: String): String {
        return value.trim().trimEnd('/')
    }

    fun load(context: Context): BackendProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BackendProfile(
            mode = BackendMode.from(prefs.getString(KEY_MODE, BackendMode.TERMUX.raw)),
            pcBaseUrl = prefs.getString(KEY_PC_BASE_URL, "") ?: "",
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        )
    }

    fun save(context: Context, profile: BackendProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, profile.mode.raw)
            .putString(KEY_PC_BASE_URL, normalizeBaseUrl(profile.pcBaseUrl))
            .putString(KEY_AUTH_TOKEN, profile.authToken.trim())
            .apply()
    }
}
