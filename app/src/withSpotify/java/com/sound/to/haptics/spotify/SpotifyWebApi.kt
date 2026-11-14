package com.sound.to.haptics.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A track's metadata only — never its audio. See class doc below. */
data class SpotifyTrackItem(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtUrl: String? = null,
    val durationMs: Long = 0L,
)

data class SpotifyPlaylistItem(
    val id: String,
    val uri: String,
    val name: String,
    val imageUrl: String? = null,
    val trackCount: Int = 0,
)

/**
 * Minimal wrapper around the Spotify Web API's read-only catalog/library
 * endpoints (search, saved tracks, top tracks) using the access token
 * obtained by [SpotifyController]'s login flow.
 *
 * This class is deliberately metadata-only: track name, artist, artwork URL,
 * duration. It never requests and Spotify never serves decoded/raw audio
 * over this API — actual playback happens exclusively through
 * [SpotifyController.play], which hands a track URI to the real Spotify app
 * via App Remote. There is no method here that returns audio bytes, and none
 * should be added; see [SpotifyController]'s doc comment for why.
 */
object SpotifyWebApi {

    private const val BASE = "https://api.spotify.com/v1"

    suspend fun search(accessToken: String, query: String, limit: Int = 25): List<SpotifyTrackItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = get("$BASE/search?q=$encoded&type=track&limit=$limit", accessToken)
                ?: return@withContext emptyList()
            parseTrackArray(json.optJSONObject("tracks")?.optJSONArray("items"))
        }

    suspend fun getTopTracks(accessToken: String, limit: Int = 30): List<SpotifyTrackItem> =
        withContext(Dispatchers.IO) {
            val json = get("$BASE/me/top/tracks?limit=$limit", accessToken) ?: return@withContext emptyList()
            parseTrackArray(json.optJSONArray("items"))
        }

    suspend fun getUserPlaylists(accessToken: String, limit: Int = 20): List<SpotifyPlaylistItem> =
        withContext(Dispatchers.IO) {
            val json = get("$BASE/me/playlists?limit=$limit", accessToken) ?: return@withContext emptyList()
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                val playlist = items.optJSONObject(index) ?: return@mapNotNull null
                val id = playlist.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uri = playlist.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val images = playlist.optJSONArray("images")
                SpotifyPlaylistItem(
                    id = id,
                    uri = uri,
                    name = playlist.optString("name", "Playlist"),
                    imageUrl = images?.optJSONObject(0)?.optString("url"),
                    trackCount = playlist.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                )
            }
        }

    suspend fun getPlaylistTracks(accessToken: String, playlistId: String, limit: Int = 50): List<SpotifyTrackItem> =
        withContext(Dispatchers.IO) {
            val json = get("$BASE/playlists/$playlistId/tracks?limit=$limit", accessToken) ?: return@withContext emptyList()
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.optJSONObject("track")?.let(::parseTrack)
            }
        }

    suspend fun getRecentlyPlayed(accessToken: String, limit: Int = 30): List<SpotifyTrackItem> =
        withContext(Dispatchers.IO) {
            val json = get("$BASE/me/player/recently-played?limit=$limit", accessToken) ?: return@withContext emptyList()
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.optJSONObject("track")?.let(::parseTrack)
            }
        }

    suspend fun getSavedTracks(accessToken: String, limit: Int = 30): List<SpotifyTrackItem> =
        withContext(Dispatchers.IO) {
            val json = get("$BASE/me/tracks?limit=$limit", accessToken) ?: return@withContext emptyList()
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.optJSONObject("track")?.let(::parseTrack)
            }
        }

    private fun parseTrackArray(items: JSONArray?): List<SpotifyTrackItem> {
        items ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::parseTrack) }
    }

    private fun parseTrack(track: JSONObject): SpotifyTrackItem? {
        val id = track.optString("id").takeIf { it.isNotBlank() } ?: return null
        val uri = track.optString("uri").takeIf { it.isNotBlank() } ?: return null
        val name = track.optString("name")
        val artists = track.optJSONArray("artists")
        val artistName = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
            .joinToString(", ")
        val images = track.optJSONObject("album")?.optJSONArray("images")
        val albumArt = images?.optJSONObject(0)?.optString("url")
        return SpotifyTrackItem(
            id = id,
            uri = uri,
            title = name,
            artist = artistName,
            albumArtUrl = albumArt,
            durationMs = track.optLong("duration_ms"),
        )
    }

    /** Returns null (rather than throwing) on any network/HTTP error — callers treat that as "no results". */
    private fun get(urlStr: String, accessToken: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
