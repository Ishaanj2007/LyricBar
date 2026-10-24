package com.example.data.repository

import android.content.Context
import com.example.data.local.LyricBarDatabase
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.example.domain.provider.LrcLibProvider
import com.example.domain.provider.LyricsOvhProvider
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CompositeLyricsRepository(
    private val database: LyricBarDatabase,
    private val providers: List<LyricsProvider> = listOf(
        LrcLibProvider(),
        LyricsOvhProvider()
    )
) {

    private val lyricsDao = database.lyricsDao()
    private val historyDao = database.songHistoryDao()

    val songHistory: Flow<List<SongHistoryEntity>> = historyDao.getRecentHistory()

    suspend fun getOrFetchLyrics(
        track: TrackMetadata,
        forceNetwork: Boolean = false
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        val key = track.key
        if (key.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid track metadata"))


        // Check Room local cache unless force network re-fetch is requested
        if (!forceNetwork) {
            val cached = lyricsDao.getLyricsByKey(key)
            if (cached != null) {
                val content = cached.syncedLrc ?: cached.plainLyrics ?: ""
                if (content.isNotBlank()) {
                    val parsed = if (content.trim().startsWith("<") || content.contains("<tt", ignoreCase = true)) {
                        com.example.domain.lyricsengine.TtmlParser.parse(
                            content = content,
                            defaultTitle = cached.title,
                            defaultArtist = cached.artist,
                            source = "${cached.source} (Cached TTML)"
                        )
                    } else {
                        LrcParser.parse(
                            content = content,
                            title = cached.title,
                            artist = cached.artist,
                            source = "${cached.source} (Cached)"
                        )
                    }
                    // Only return cached if valid or if remote network is not strictly needed
                    if (parsed.isSynced || !cached.isSynced) {
                        return@withContext Result.success(parsed)
                    }
                }
            }
        }

        // Clean title & artist for web queries (strip (Official Video), [Remastered], etc.)
        val cleanTitle = TrackMetadata.cleanString(track.title)
        val cleanArtist = TrackMetadata.cleanString(track.artist)

        for (provider in providers) {
            try {
                val result = provider.fetchLyrics(cleanTitle, cleanArtist, track.durationSec)
                if (result.isSuccess) {
                    val lyrics = result.getOrThrow()
                    // Cache in database with raw timed content preserved
                    val rawToCache = if (lyrics.rawContent.isNotBlank()) lyrics.rawContent else lyrics.plainText
                    lyricsDao.insertLyrics(
                        LyricsEntity(
                            key = key,
                            title = track.title,
                            artist = track.artist,
                            syncedLrc = if (lyrics.isSynced) rawToCache else null,
                            plainLyrics = lyrics.plainText,
                            isSynced = lyrics.isSynced,
                            durationSec = track.durationSec,
                            source = provider.name,
                            cachedAt = System.currentTimeMillis()
                        )
                    )
                    return@withContext Result.success(lyrics)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Continue to next provider in fallback order
            }
        }

        Result.failure(Exception("Lyrics not found on remote providers"))
    }

    suspend fun saveImportedLyrics(
        track: TrackMetadata,
        content: String,
        source: String = "Imported"
    ): ParsedLyrics = withContext(Dispatchers.IO) {
        val parsed = if (content.trim().startsWith("<") || content.contains("<tt", ignoreCase = true)) {
            com.example.domain.lyricsengine.TtmlParser.parse(
                content = content,
                defaultTitle = track.title,
                defaultArtist = track.artist,
                source = source
            )
        } else {
            LrcParser.parse(
                content = content,
                title = track.title,
                artist = track.artist,
                source = source
            )
        }

        lyricsDao.insertLyrics(
            LyricsEntity(
                key = track.key,
                title = track.title,
                artist = track.artist,
                syncedLrc = if (parsed.isSynced) content else null,
                plainLyrics = parsed.plainText,
                isSynced = parsed.isSynced,
                durationSec = track.durationSec,
                source = source,
                cachedAt = System.currentTimeMillis()
            )
        )
        parsed
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        lyricsDao.clearAllLyrics()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
    }

    suspend fun recordHistory(
        track: TrackMetadata,
        status: String = "Played"
    ) = withContext(Dispatchers.IO) {
        val key = track.key
        if (key.isBlank()) return@withContext
        try {
            historyDao.insertHistory(
                SongHistoryEntity(
                    key = key,
                    title = track.title,
                    artist = track.artist,
                    albumArtUri = track.albumArtUri,
                    status = status,
                    playedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: CompositeLyricsRepository? = null

        fun getInstance(context: Context): CompositeLyricsRepository {
            return INSTANCE ?: synchronized(this) {
                val db = LyricBarDatabase.getInstance(context)
                val repo = CompositeLyricsRepository(db)
                INSTANCE = repo
                repo
            }
        }
    }
}
