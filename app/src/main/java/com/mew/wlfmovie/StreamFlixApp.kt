package com.mew.wlfmovie

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import java.security.Security
import org.conscrypt.Conscrypt
import com.mew.wlfmovie.BuildConfig
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.providers.AniWorldProvider
import com.mew.wlfmovie.providers.SerienStreamProvider
import com.mew.wlfmovie.utils.AppLanguageManager
import com.mew.wlfmovie.utils.ArtworkRepairScheduler
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.CrashHandler
import com.mew.wlfmovie.utils.CrashLogger
import com.mew.wlfmovie.utils.DnsResolver
import com.mew.wlfmovie.utils.IsrgRootTrustProvider
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamFlixApp : Application() {
    companion object {
        lateinit var instance: StreamFlixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // WLFMOVIE V6.2: Instalar el crash handler y el logger persistente
        // lo antes posible, antes de cualquier otra inicializacion que pueda
        // crashear. Cualquier excepcion no atrapada en cualquier hilo queda
        // escrita en filesDir/wlfmovie-errors.log y se muestra al usuario en
        // el siguiente arranque via LastCrashDialog.
        //
        // WLFMOVIE V6.2.3: En release NO inicializamos el CrashLogger
        // (no crea archivo en disco, no escribe nada, no consume I/O).
        // CrashHandler SI se instala en release — pero su handleUncaught()
        // interno hace early return en release, asi que solo logcat sin
        // persistencia. Resultado: en release no hay rastro en disco de
        // crashes ni errores, en debug el log funciona como siempre.
        try {
            if (BuildConfig.DEBUG) {
                CrashLogger.init(this)
            }
            CrashHandler.install(this)
        } catch (t: Throwable) {
            // Si el propio handler falla al instalar, no rompamos la app.
            android.util.Log.e("WlfMovie-App", "No se pudo instalar CrashHandler", t)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        // 0. Initialize Conscrypt for modern SSL on old Android
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        IsrgRootTrustProvider.install()

        // 2. Inizializzazione preferenze (con applicationContext)
        UserPreferences.setup(this)

        // WLFMOVIE V6.4.1: configurar DNS DESPUES de setup() y con guarda —
        // un fallo aqui jamas debe tumbar la app en el arranque (antes era un
        // NoClassDefFoundError FATAL por una clase mal inicializada en otro hilo).
        try {
            DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)
        } catch (t: Throwable) {
            android.util.Log.e("WlfMovie-App", "No se pudo configurar el DNS, se usara el del sistema", t)
        }

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            SerienStreamProvider.initialize(appContext)
            AniWorldProvider.initialize(appContext)
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)

            // WLFMOVIE V6.6.0: la conversión TS→MP4 (y la re-codificación de
            // audio AAC que llevaba) se ELIMINÓ por completo — ya no existe la
            // cola de conversión ni la preferencia. Las descargas se sirven
            // tal cual viven en disco (.ts por HLS, .mp4 por Remux).
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}
