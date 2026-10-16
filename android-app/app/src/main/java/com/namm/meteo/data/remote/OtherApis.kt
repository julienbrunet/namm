package com.namm.meteo.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** Radar de pluie observé : https://www.rainviewer.com/api.html */
interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun maps(): RainViewerMapsDto

    companion object {
        fun create(): RainViewerApi = Network.create("https://api.rainviewer.com/")
    }
}

@Serializable
data class RainViewerMapsDto(val host: String, val radar: RadarDto)

@Serializable
data class RadarDto(val past: List<RadarFrameDto> = emptyList())

@Serializable
data class RadarFrameDto(val time: Long, val path: String)

/** Releases GitHub, pour la mise à jour automatique. */
interface GitHubApi {
    @GET("repos/julienbrunet/namm/releases/latest")
    suspend fun latestRelease(): ReleaseDto

    companion object {
        fun create(): GitHubApi = Network.create("https://api.github.com/")
    }
}

@Serializable
data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)
