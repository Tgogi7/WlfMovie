package com.mew.wlfmovie.utils

import android.util.Log
import com.mew.wlfmovie.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE V4: Cliente de Supabase para auth y sync.
 */
object SupabaseClient {

    private const val TAG = "WlfMovie-Supabase"

    private val CLEAN_URL: String = run {
        var url = BuildConfig.SUPABASE_URL.trimEnd('/')
        if (url.endsWith("/rest/v1")) {
            url = url.removeSuffix("/rest/v1")
        }
        url
    }
    private val BASE_URL = "$CLEAN_URL/rest/v1"
    private val API_KEY = BuildConfig.SUPABASE_ANON_KEY

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class User(
        val id: Long,
        val email: String,
        val username: String,
        val syncData: String?,
        val lastSync: String?
    )

    sealed class AuthResult {
        data class Success(val user: User) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Genera timestamp ISO 8601 para PostgreSQL */
    private fun nowISO(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    suspend fun register(email: String, password: String, username: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val existing = queryUserByEmail(email)
            if (existing != null) {
                return@withContext AuthResult.Error("El email ya está registrado")
            }

            val json = JSONObject().apply {
                put("email", email)
                put("password_hash", hashPassword(password))
                put("username", username)
                put("sync_data", JSONObject().toString())
            }

            val request = Request.Builder()
                .url("$BASE_URL/wlf_users")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Error registrando: ${response.code} - $body")
                val errorMsg = when (response.code) {
                    401 -> "Error de autenticación con el servidor"
                    403 -> "Permisos insuficientes (revisar RLS en Supabase)"
                    409 -> "El email ya está registrado"
                    else -> "Error del servidor (${response.code})"
                }
                return@withContext AuthResult.Error(errorMsg)
            }

            val arr = JSONArray(body)
            if (arr.length() == 0) {
                return@withContext AuthResult.Error("Respuesta vacía del servidor")
            }

            val obj = arr.getJSONObject(0)
            val user = User(
                id = obj.getLong("id"),
                email = obj.getString("email"),
                username = obj.getString("username"),
                syncData = obj.optString("sync_data", null),
                lastSync = obj.optString("last_sync", null)
            )

            Log.i(TAG, "Usuario registrado OK: ${user.email} (id=${user.id})")
            AuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Error register: ${e.message}", e)
            AuthResult.Error("Error de conexión: ${e.message}")
        }
    }

    suspend fun login(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/wlf_users?select=id,email,username,password_hash,sync_data,last_sync&email=eq.$encodedEmail")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Error login: ${response.code} - $body")
                return@withContext AuthResult.Error("Error del servidor (${response.code})")
            }

            val arr = JSONArray(body)
            if (arr.length() == 0) {
                return@withContext AuthResult.Error("Email no encontrado")
            }

            val obj = arr.getJSONObject(0)
            val storedHash = obj.getString("password_hash")
            val hashedInput = hashPassword(password)

            if (storedHash != hashedInput) {
                return@withContext AuthResult.Error("Contraseña incorrecta")
            }

            val user = User(
                id = obj.getLong("id"),
                email = obj.getString("email"),
                username = obj.getString("username"),
                syncData = obj.optString("sync_data", null),
                lastSync = obj.optString("last_sync", null)
            )

            Log.i(TAG, "Login OK: ${user.email} (id=${user.id})")
            AuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Error login: ${e.message}", e)
            AuthResult.Error("Error de conexión: ${e.message}")
        }
    }

    /**
     * Sube el sync_data encriptado del usuario.
     * WLFMOVIE: last_sync se envía con timestamp actual (no NULL).
     */
    suspend fun uploadSyncData(userId: Long, syncData: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("sync_data", syncData)
                put("last_sync", nowISO())
            }

            val request = Request.Builder()
                .url("$BASE_URL/wlf_users?id=eq.$userId")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .header("Content-Type", "application/json")
                .patch(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "Error upload sync: ${response.code} - ${response.body?.string()}")
            } else {
                Log.i(TAG, "Upload sync OK para userId=$userId")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error uploadSyncData: ${e.message}")
            false
        }
    }

    suspend fun downloadSyncData(userId: Long): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/wlf_users?select=sync_data,last_sync&id=eq.$userId")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error download: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null

            val obj = arr.getJSONObject(0)
            val syncData = if (obj.isNull("sync_data") || obj.optString("sync_data").isBlank()) null
                           else obj.getString("sync_data")
            val lastSync = if (obj.isNull("last_sync")) null else obj.getString("last_sync")

            Log.i(TAG, "Download sync: syncData=${if (syncData != null) "${syncData.length} chars" else "null"}, lastSync=$lastSync")

            Pair(syncData, lastSync)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloadSyncData: ${e.message}")
            null
        }
    }

    suspend fun getLastSync(userId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/wlf_users?select=last_sync&id=eq.$userId")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null

            val obj = arr.getJSONObject(0)
            if (obj.isNull("last_sync")) null else obj.getString("last_sync")
        } catch (e: Exception) {
            Log.e(TAG, "Error getLastSync: ${e.message}")
            null
        }
    }

    private suspend fun queryUserByEmail(email: String): User? = withContext(Dispatchers.IO) {
        try {
            val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/wlf_users?select=id,email,username,sync_data,last_sync&email=eq.$encodedEmail")
                .header("apikey", API_KEY)
                .header("Authorization", "Bearer $API_KEY")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null

            val obj = arr.getJSONObject(0)
            User(
                id = obj.getLong("id"),
                email = obj.getString("email"),
                username = obj.getString("username"),
                syncData = obj.optString("sync_data", null),
                lastSync = obj.optString("last_sync", null)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error queryUserByEmail: ${e.message}")
            null
        }
    }
}
