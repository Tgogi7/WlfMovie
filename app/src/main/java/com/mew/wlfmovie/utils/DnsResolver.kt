package com.mew.wlfmovie.utils

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.InetAddress

/**
 * Resolver DNS de la app (DoH con fallback al DNS del sistema).
 *
 * WLFMOVIE V6.4.1 — FIX crash FATAL en el primer arranque:
 *
 * ANTES el object hacia esto al cargar la clase (<clinit>):
 *   `_url = UserPreferences.dohProviderUrl`
 *
 * WorkManager puede construir AniWorldUpdateTvShowWorker en un hilo propio
 * ANTES de que StreamFlixApp.onCreate() llegue a ejecutar
 * UserPreferences.setup(...). Eso disparaba la cadena:
 *   worker.<init> -> AniWorldProvider.<clinit> -> Service.build()
 *   -> DnsResolver.<clinit> -> UserPreferences.prefs (lateinit SIN inicializar)
 *   -> UninitializedPropertyAccessException -> ExceptionInInitializerError
 *   -> clase DnsResolver "envenenada" -> el hilo main crashea con
 *   NoClassDefFoundError en DnsResolver.setDnsUrl(...) de onCreate.
 *
 * AHORA:
 * - El <clinit> NO toca UserPreferences ni construye nada: arranca con
 *   DNS del sistema y queda listo de forma trivial y a prueba de fallos.
 * - El cliente OkHttp para DoH se construye lazy (solo si se configura un DoH).
 * - setDnsUrl() se llama desde App.onCreate DESPUES de UserPreferences.setup().
 *   Si se llama antes (o desde cualquier hilo), simplemente funciona con lo que le pasen.
 * - lookup() nunca depende del estado de inicializacion de la app.
 */
object DnsResolver : Dns {
    private const val TAG = "DnsResolver"

    /** URL del DoH configurado; null = no configurado (DNS del sistema). */
    @Volatile
    private var configuredUrl: String? = null

    /** DNS en uso. Arranca en SYSTEM; se reemplaza al configurar un DoH. */
    @Volatile
    private var internalDns: Dns = Dns.SYSTEM

    /**
     * Cliente para las consultas DoH. Lazy: solo se construye si alguna vez
     * se configura un DoH. Nunca se toca durante el <clinit>.
     */
    private val dohClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
            OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                .build()
        } catch (e: Exception) {
            // Nunca debe fallar, pero si falla devolvemos un cliente plano:
            // buildDoh() ya tiene su propio fallback a Dns.SYSTEM.
            Log.e(TAG, "Error construyendo cliente DoH, usando cliente plano", e)
            OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val dns = internalDns
        return try {
            dns.lookup(hostname)
        } catch (e: Exception) {
            if (dns === Dns.SYSTEM) {
                throw e
            }
            Log.w(TAG, "Fallo DoH para $hostname, fallback a SYSTEM DNS: ${e.message}")
            Dns.SYSTEM.lookup(hostname)
        }
    }

    val doh: Dns get() = this

    @Synchronized
    fun setDnsUrl(newUrl: String) {
        if (newUrl == configuredUrl) {
            Log.d(TAG, "DNS URL igual a la actual ('$newUrl'), no se actualiza")
            return
        }
        configuredUrl = newUrl
        internalDns = if (newUrl.isNotEmpty()) {
            try {
                DnsOverHttps.Builder()
                    .client(dohClient)
                    .url(newUrl.toHttpUrl())
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo construir DoH para '$newUrl', usando SYSTEM DNS: ${e.message}")
                Dns.SYSTEM
            }
        } else {
            Log.d(TAG, "DoH deshabilitado, usando SYSTEM DNS")
            Dns.SYSTEM
        }
        Log.i(TAG, "DNS actualizado a: ${if (newUrl.isEmpty()) "SYSTEM" else newUrl}")
    }
}
