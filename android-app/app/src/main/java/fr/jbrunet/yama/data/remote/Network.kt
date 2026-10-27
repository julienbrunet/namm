package fr.jbrunet.yama.data.remote

import fr.jbrunet.yama.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/**
 * Open-Meteo limits calls per minute and per IP (each point of a grid counts).
 * As soon as a 429 is received, note until when the service is saturated: the map holds back
 * meanwhile to let the home screen through, and the home screen shows a clear reason.
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

/** Thrown instead of calling Open-Meteo while [OpenMeteoQuota] is blocked. */
class OpenMeteoBusyException : java.io.IOException("Open-Meteo rate limit reached, retrying in a minute")

object Network {
    val json = Json { ignoreUnknownKeys = true }

    /** Identifies the app to the services (required by the OSM tile usage policy). */
    val userAgent = "Yama/${BuildConfig.VERSION_NAME} (+https://github.com/julienbrunet/yama)"

    @Volatile
    private var cacheDir: File? = null

    /** To be called when the app starts (before any network call) to enable the HTTP disk cache. */
    fun install(cacheDir: File) {
        this.cacheDir = cacheDir
    }

    /**
     * Single client for the app: one pool of connections (HTTP/2, reused TLS) and threads,
     * with a 20 MB disk cache (mostly radar tiles).
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
                // A RainViewer radar tile never changes (its URL contains the image time):
                // keep it 2 h in the disk cache, whatever the server headers say.
                val url = chain.request().url
                if (response.isSuccessful && url.host.endsWith("rainviewer.com") && url.encodedPath.endsWith(".png")) {
                    response.newBuilder().header("Cache-Control", "public, max-age=7200").removeHeader("Pragma").build()
                } else {
                    response
                }
            }
            .build()
    }

    /** Same connection pool, without HTTP cache (files already cached elsewhere or not reusable). */
    val uncachedClient: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    inline fun <reified T> create(baseUrl: String): T = retrofit(baseUrl).create(T::class.java)

    fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
