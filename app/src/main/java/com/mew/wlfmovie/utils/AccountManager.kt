package com.mew.wlfmovie.utils

import android.content.Context

/**
 * WLFMOVIE V4: Gestor de sesión.
 * Mantiene el estado de login en SharedPreferences.
 */
object AccountManager {

    private const val PREFS_NAME = "wlfmovie_account"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_USERNAME = "username"
    private const val KEY_LAST_SYNC = "last_sync"

    data class Session(
        val userId: Long,
        val email: String,
        val username: String
    )

    fun getSession(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getLong(KEY_USER_ID, -1)
        if (userId == -1L) return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        return Session(userId, email, username)
    }

    fun saveSession(context: Context, userId: Long, email: String, username: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun getLastSync(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNC, null)
    }

    fun saveLastSync(context: Context, timestamp: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SYNC, timestamp)
            .apply()
    }

    fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        if (!email.contains("@")) return false
        val parts = email.split("@")
        if (parts.size != 2) return false
        val domain = parts[1].lowercase()
        if (!domain.contains(".")) return false
        val tld = domain.substringAfterLast(".")
        if (tld.length < 2) return false

        val blockedDomains = listOf(
            "example.com", "example.org", "example.net",
            "x.com", "x.org", "x.net",
            "temp.com", "tempmail.com", "temp-mail.com",
            "test.com", "test.org",
            "fake.com", "fake.org",
            "mail.com", "mailinator.com",
            "trash.com", "trashmail.com",
            "yopmail.com", "guerrillamail.com",
            "10minutemail.com", "dispostable.com",
            "sharklasers.com", "throwawaymail.com"
        )

        if (domain in blockedDomains) return false
        return true
    }
}
