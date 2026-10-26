package com.namm.meteo.data.remote

import com.namm.meteo.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/**
 * Open-Meteo limite les appels par minute et par IP (chaque point d'une grille compte).
 * Dès qu'un 429 est reçu, on note jusqu'à quand le service est saturé : la carte s'abstient
 * pendant ce temps pour laisser passer l'accueil, et l'accueil affiche une raison claire.
 */
object OpenMeteoQuota {
    @Volatile
    var blockedUntilMillis: Long = 0
        private set

    val isBlocked: Boolean get() = System.currentTimeMillis() < blockedUntilMillis

    fun block(seconds: Long) {
        blockedUntilMillis = System.currentTimeMillis() + seconds * 1000
    }
}

object Network {
    val json = Json { ignoreUnknownKeys = true }

    /** Identifie l'appli auprès des services (exigé par la politique d'usage des tuiles OSM). */
    val userAgent = "NoAdMeteo/${BuildConfig.VERSION_NAME} (+https://github.com/julienbrunet/namm)"

    @Volatile
    private var cacheDir: File? = null

    /** À appeler au démarrage de l'appli (avant tout appel réseau) pour activer le cache HTTP disque. */
    fun install(cacheDir: File) {
        this.cacheDir = cacheDir
    }

    /**
     * Client unique de l'appli : un seul pool de connexions (HTTP/2, TLS réutilisé) et de threads,
     * avec un cache disque de 20 Mo (tuiles radar surtout).
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { cacheDir?.let { cache(Cache(File(it, "http"), 20L * 1024 * 1024)) } }
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                if (response.code == 429 && chain.request().url.host.endsWith("open-meteo.com")) {
                    OpenMeteoQuota.block(response.header("Retry-After")?.toLongOrNull() ?: 60)
                }
                response
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Une tuile radar RainViewer ne change jamais (son URL contient l'heure de l'image) :
                // on la garde 2 h dans le cache disque, quels que soient les en-têtes du serveur.
                val url = chain.request().url
                if (response.isSuccessful && url.host.endsWith("rainviewer.com") && url.encodedPath.endsWith(".png")) {
                    response.newBuilder().header("Cache-Control", "public, max-age=7200").removeHeader("Pragma").build()
                } else {
                    response
                }
            }
            .build()
    }

    /** Même pool de connexions, sans cache HTTP (fichiers déjà mis en cache ailleurs ou non réutilisables). */
    val uncachedClient: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    inline fun <reified T> create(baseUrl: String): T = retrofit(baseUrl).create(T::class.java)

    fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
