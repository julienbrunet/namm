package com.namm.meteo.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

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

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request().newBuilder().header("User-Agent", "NoAdMeteo-Android").build())
            if (response.code == 429 && chain.request().url.host.endsWith("open-meteo.com")) {
                OpenMeteoQuota.block(response.header("Retry-After")?.toLongOrNull() ?: 60)
            }
            response
        }
        .build()

    inline fun <reified T> create(baseUrl: String): T = retrofit(baseUrl).create(T::class.java)

    fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
