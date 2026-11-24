package com.example.domain.provider

import com.example.BuildConfig
import com.example.data.model.ParsedLyrics
import com.example.domain.parser.LrcParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LrcLibProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : LyricsProvider {

    override val name: String = "LRCLIB"

    companion object {
        // MIN-04: Dynamically format User-Agent using BuildConfig.VERSION_NAME rather than stale hardcoded version
        private val USER_AGENT: String = "LyricBar/${try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "2.6.1" }} (Android)"
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid LRCLIB URL"))

            urlBuilder.addQueryParameter("track_name", title.trim())
            urlBuilder.addQueryParameter("artist_name", artist.trim())
            if (durationSec != null && durationSec > 0) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val getResult = client.newCall(request).awaitLrcLibResponse()
            if (getResult.code == 429) {
                return@withContext Result.failure(IOException("LRCLIB rate limit exceeded (HTTP 429)"))
            }
            if (getResult.code in 500..599) {
                return@withContext Result.failure(IOException("LRCLIB returned HTTP ${getResult.code}"))
            }

            if (getResult.code in 200..299) {
                val bodyString = getResult.body
                if (bodyString.isNotBlank()) {
                    val json = JSONObject(bodyString)
                    val syncedLrc = json.optCleanLyricsString("syncedLyrics")
                    val plainLyrics = json.optCleanLyricsString("plainLyrics")
                    val trackName = json.optCleanLyricsString("trackName") ?: title
                    val artistName = json.optCleanLyricsString("artistName") ?: artist

                    if (!syncedLrc.isNullOrBlank()) {
                        val parsed = LrcParser.parse(
                            content = syncedLrc,
                            title = trackName,
                            artist = artistName,
                            source = name
                        )
                        if (parsed.hasValidLyrics) {
                            return@withContext Result.success(parsed)
                        }
                    }
                    if (!plainLyrics.isNullOrBlank()) {
                        val parsed = LrcParser.parse(
                            content = plainLyrics,
                            title = trackName,
                            artist = artistName,
                            source = "$name (Plain)"
                        )
                        if (parsed.hasValidLyrics) {
                            return@withContext Result.success(parsed)
                        }
                    }
                }
            }

            // Fallback: Query LRCLIB search endpoint when exact match is not found or not 2xx
            val searchQuery = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
            if (searchQuery.isNotBlank()) {
                val searchUrl = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("q", searchQuery)
                    ?.build()

                if (searchUrl != null) {
                    val searchRequest = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()

                    val searchResult = client.newCall(searchRequest).awaitLrcLibResponse()
                    if (searchResult.code == 429) {
                        return@withContext Result.failure(IOException("LRCLIB rate limit exceeded (HTTP 429)"))
                    }
                    if (searchResult.code in 500..599) {
                        return@withContext Result.failure(IOException("LRCLIB returned HTTP ${searchResult.code}"))
                    }

                    if (searchResult.code in 200..299) {
                        val sBody = searchResult.body
                        if (sBody.isNotBlank() && sBody.trim().startsWith("[")) {
                            val array = org.json.JSONArray(sBody)
                            var firstPlain: ParsedLyrics? = null
                            for (i in 0 until array.length().coerceAtMost(6)) {
                                val item = array.optJSONObject(i) ?: continue
                                val synced = item.optCleanLyricsString("syncedLyrics")
                                val plain = item.optCleanLyricsString("plainLyrics")
                                val tName = item.optCleanLyricsString("trackName") ?: title
                                val aName = item.optCleanLyricsString("artistName") ?: artist

                                if (!synced.isNullOrBlank()) {
                                    val parsed = LrcParser.parse(
                                        content = synced,
                                        title = tName,
                                        artist = aName,
                                        source = name
                                    )
                                    if (parsed.hasValidLyrics) {
                                        return@withContext Result.success(parsed)
                                    }
                                } else if (!plain.isNullOrBlank() && firstPlain == null) {
                                    val parsed = LrcParser.parse(
                                        content = plain,
                                        title = tName,
                                        artist = aName,
                                        source = "$name (Plain)"
                                    )
                                    if (parsed.hasValidLyrics) {
                                        firstPlain = parsed
                                    }
                                }
                            }
                            if (firstPlain != null && firstPlain.hasValidLyrics) {
                                return@withContext Result.success(firstPlain)
                            }
                        }
                    }
                }
            }

            Result.failure(NoSuchElementException("Lyrics not found on LRCLIB"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private data class LrcLibHttpResult(
    val code: Int,
    val body: String
)

private suspend fun Call.awaitLrcLibResponse(): LrcLibHttpResult = suspendCancellableCoroutine { cont ->
    val isResumed = AtomicBoolean(false)
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {}
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (isResumed.compareAndSet(false, true) && cont.isActive) {
                cont.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            var result: LrcLibHttpResult? = null
            var error: Throwable? = null
            response.use { resp ->
                try {
                    val body = resp.body?.string().orEmpty()
                    result = LrcLibHttpResult(resp.code, body)
                } catch (e: Throwable) {
                    error = e
                }
            }
            if (isResumed.compareAndSet(false, true) && cont.isActive) {
                val err = error
                if (err != null) {
                    cont.resumeWithException(err)
                } else {
                    cont.resume(result!!)
                }
            }
        }
    })
}

private fun JSONObject.optCleanLyricsString(key: String): String? {
    if (isNull(key)) return null
    val raw = opt(key) ?: return null
    if (raw == JSONObject.NULL) return null
    val str = raw.toString().trim()
    return if (str.isEmpty() || str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
        null
    } else {
        str
    }
}
