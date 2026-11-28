package com.example.domain.lyricsengine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Shared, provider-agnostic lyrics loading messages and calm rotation logic
 * used across both LyricsScreen and NowPlayingScreen.
 */
object LyricsLoadingMessages {

    const val LOADING_MESSAGE_INTERVAL_MS: Long = 2800L

    val messages: List<String> = listOf(
        "Searching for synchronized lyrics…",
        "Hunting down every word…",
        "Checking online lyric sources…",
        "Finding the words behind the music…",
        "Tracking down those missing verses…",
        "Looking for timed lines…",
        "Brewing your lyrics…",
        "Gathering every verse for you…",
        "On the hunt for your lyrics…",
        "Turning melodies into words…",
        "Knocking on the provider's door…",
        "Searching high and low for your lyrics…"
    )

    /**
     * Picks a deterministic starting index for [trackKey] so different tracks
     * start with varied messages without flickering on recomposition.
     */
    fun pickInitialIndex(trackKey: String): Int {
        if (messages.isEmpty()) return 0
        if (trackKey.isBlank()) return 0
        return abs(trackKey.hashCode()) % messages.size
    }

    /**
     * Advances to the next message index without repeating the current message.
     */
    fun nextIndex(currentIndex: Int): Int {
        if (messages.size <= 1) return 0
        val normalized = ((currentIndex % messages.size) + messages.size) % messages.size
        return (normalized + 1) % messages.size
    }

    fun messageAt(index: Int): String {
        if (messages.isEmpty()) return "Searching for lyrics…"
        val normalized = ((index % messages.size) + messages.size) % messages.size
        return messages[normalized]
    }
}

/**
 * Remembers and calmly rotates a loading message every [LyricsLoadingMessages.LOADING_MESSAGE_INTERVAL_MS]
 * while [isLoading] is true. Resets immediately when [trackKey] changes and stops when loading ends.
 */
@Composable
fun rememberRotatingLoadingMessage(
    isLoading: Boolean,
    trackKey: String
): String {
    var messageIndex by remember(trackKey) {
        mutableIntStateOf(LyricsLoadingMessages.pickInitialIndex(trackKey))
    }

    LaunchedEffect(isLoading, trackKey) {
        if (!isLoading) return@LaunchedEffect
        messageIndex = LyricsLoadingMessages.pickInitialIndex(trackKey)
        while (isActive) {
            delay(LyricsLoadingMessages.LOADING_MESSAGE_INTERVAL_MS)
            if (!isActive) break
            messageIndex = LyricsLoadingMessages.nextIndex(messageIndex)
        }
    }

    return LyricsLoadingMessages.messageAt(messageIndex)
}
