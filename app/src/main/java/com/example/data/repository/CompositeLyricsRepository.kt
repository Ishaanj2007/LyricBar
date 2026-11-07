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

class CompositeLyricsRepository internal constructor(
    private val lyricsDao: com.example.data.local.dao.LyricsDao,
    private val historyDao: com.example.data.local.dao.SongHistoryDao,
    private val providers: List<LyricsProvider> = listOf(
        LrcLibProvider(),
        LyricsOvhProvider()
    )
) {

    constructor(
        database: LyricBarDatabase,
        providers: List<LyricsProvider> = listOf(
            LrcLibProvider(),
            LyricsOvhProvider()
        )
    ) : this(
        lyricsDao = database.lyricsDao(),
        historyDao = database.songHistoryDao(),
        providers = providers
    )

    val songHistory: Flow<List<SongHistoryEntity>> = historyDao.getRecentHistory()

    suspend fun getOrFetchLyrics(
        track: TrackMetadata,
        forceNetwork: Boolean = false,
        allowNetwork: Boolean = true
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        val identityKey = track.identityKey
        val lookupKey = track.lookupKey
        val baseKey = track.baseIdentityKey

        if (identityKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid track metadata"))

        // Check Room local cache unless force network re-fetch is requested
        if (!forceNetwork) {
            var cached = lyricsDao.getLyricsByKey(identityKey)
            if (cached == null && identityKey != baseKey) {
                cached = lyricsDao.getLyricsByKey(baseKey)
            }
            if (cached == null && identityKey != lookupKey && baseKey != lookupKey) {
                val legacy = lyricsDao.getLyricsByKey(lookupKey)
                if (legacy != null && isSafeLegacyMatch(track, legacy)) {
                    cached = legacy
                    // Forward migrate to identityKey so subsequent lookups hit identityKey directly
                    try {
                        lyricsDao.insertLyrics(legacy.copy(key = identityKey))
                    } catch (_: Exception) {}
                }
            }

            if (cached != null) {
                val rawContent = (cached.syncedLrc ?: cached.plainLyrics ?: "").trim()
                if (rawContent.isNotBlank() &&
                    !rawContent.equals("null", ignoreCase = true) &&
                    !rawContent.equals("undefined", ignoreCase = true) &&
                    !LrcParser.isTtml(rawContent)
                ) {
                    val parsed = LrcParser.parse(
                        content = rawContent,
                        title = cached.title,
                        artist = cached.artist,
                        source = if (cached.source.endsWith("(Cached)")) cached.source else "${cached.source} (Cached)"
                    )
                    // Only return cached if valid or if remote network is not strictly needed
                    if (parsed.hasValidLyrics && (parsed.isSynced || !cached.isSynced)) {
                        return@withContext Result.success(parsed)
                    }
                }
                // Purge corrupted or empty cached entry (e.g. from poisoned cache containing literal "null")
                try {
                    lyricsDao.deleteLyricsByKey(cached.key)
                } catch (_: Exception) {}
            }
        }

        // If network fetching is disabled, do not perform remote provider queries
        if (!allowNetwork) {
            return@withContext Result.failure(Exception("Automatic lyric fetching is disabled"))
        }

        // Clean title & artist for web queries (strip (Official Video), [Remastered], etc.)
        val cleanTitle = TrackMetadata.cleanForLookup(track.title)
        val cleanArtist = TrackMetadata.cleanForLookup(track.artist)

        for (provider in providers) {
            try {
                val result = provider.fetchLyrics(cleanTitle, cleanArtist, track.durationSec)
                if (result.isSuccess) {
                    val lyrics = result.getOrThrow()
                    // Reject unsupported TTML content if returned by any provider
                    if (LrcParser.isTtml(lyrics.rawContent) || LrcParser.isTtml(lyrics.plainText) || !lyrics.hasValidLyrics) {
                        continue
                    }
                    // Cache in database under track.identityKey to prevent version collisions
                    val rawToCache = if (lyrics.rawContent.isNotBlank()) lyrics.rawContent else lyrics.plainText
                    lyricsDao.insertLyrics(
                        LyricsEntity(
                            key = identityKey,
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
        if (content.isBlank()) {
            throw IllegalArgumentException("Lyric file is empty")
        }
        if (content.contains('\u0000')) {
            throw IllegalArgumentException("Unsupported file: binary files are not supported.")
        }
        val identityKey = track.identityKey
        if (identityKey.isBlank()) {
            throw IllegalArgumentException("Invalid track metadata")
        }

        val parsed = LrcParser.parse(
            content = content,
            title = track.title,
            artist = track.artist,
            source = source
        )

        if (parsed.lines.isEmpty() || parsed.lines.all { it.text.isBlank() } || !parsed.hasValidLyrics) {
            throw IllegalArgumentException("No valid lyrics found in file")
        }

        lyricsDao.insertLyrics(
            LyricsEntity(
                key = identityKey,
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

    suspend fun deleteHistory(id: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryById(id)
    }

    suspend fun recordHistory(
        track: TrackMetadata,
        status: String = "Played"
    ) = withContext(Dispatchers.IO) {
        val title = track.title.trim()
        val artist = track.artist.trim()
        val identityKey = track.identityKey
        if (title.isBlank() || identityKey.isBlank() || identityKey.startsWith("unknown artist - unknown title")) {
            return@withContext
        }
        try {
            historyDao.insertHistory(
                SongHistoryEntity(
                    key = identityKey,
                    title = title,
                    artist = if (artist.isBlank()) "Unknown Artist" else artist,
                    albumArtUri = track.albumArtUri?.trim()?.takeIf { it.isNotBlank() },
                    status = status,
                    playedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {}
    }

    companion object {
        internal fun isSafeLegacyMatch(track: TrackMetadata, legacy: LyricsEntity): Boolean {
            val trackArtist = TrackMetadata.cleanForLookup(track.artist)
            val legacyArtist = TrackMetadata.cleanForLookup(legacy.artist)
            if (trackArtist.isNotBlank() && legacyArtist.isNotBlank() && !trackArtist.equals(legacyArtist, ignoreCase = true)) {
                return false
            }

            val trackTitleLower = track.title.lowercase()
            val legacyTitleLower = legacy.title.lowercase()

            val isLiveTrack = trackTitleLower.contains("live")
            val isLiveLegacy = legacyTitleLower.contains("live")
            if (isLiveTrack != isLiveLegacy) return false

            val isAcousticTrack = trackTitleLower.contains("acoustic") || trackTitleLower.contains("unplugged")
            val isAcousticLegacy = legacyTitleLower.contains("acoustic") || legacyTitleLower.contains("unplugged")
            if (isAcousticTrack != isAcousticLegacy) return false

            val isRadioEditTrack = trackTitleLower.contains("radio edit") || trackTitleLower.contains("single edit")
            val isRadioEditLegacy = legacyTitleLower.contains("radio edit") || legacyTitleLower.contains("single edit")
            if (isRadioEditTrack != isRadioEditLegacy) return false

            val isInstrumentalTrack = trackTitleLower.contains("instrumental")
            val isInstrumentalLegacy = legacyTitleLower.contains("instrumental")
            if (isInstrumentalTrack != isInstrumentalLegacy) return false

            if (track.durationSec > 0 && legacy.durationSec != null && legacy.durationSec > 0) {
                if (Math.abs(track.durationSec - legacy.durationSec) > 15) {
                    return false
                }
            }

            return true
        }

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
