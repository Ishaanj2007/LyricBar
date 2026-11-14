package com.example.updater

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.updater.NotificationPostResult
import com.example.data.updater.ReleaseInfo
import com.example.data.updater.UpdateCheckEngine
import com.example.data.updater.UpdateCheckOutcome
import com.example.data.updater.UpdateCheckScheduler
import com.example.data.updater.UpdateCheckTrigger
import com.example.data.updater.UpdateCheckWorker
import com.example.data.updater.UpdateFetchResult
import com.example.data.updater.UpdateNotificationPoster
import com.example.data.updater.UpdateNotifier
import com.example.data.updater.UpdateRepository
import com.example.data.updater.UpdateScheduleBackend
import com.example.data.updater.VersionComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AutoUpdateWorkerAndNotificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeNotificationPoster(
        var nextResult: NotificationPostResult = NotificationPostResult.Posted,
        var notificationsAllowed: Boolean = true
    ) : UpdateNotificationPoster {
        val postedReleases = CopyOnWriteArrayList<ReleaseInfo>()
        var channelEnsuredCount = 0

        override fun ensureNotificationChannel() {
            channelEnsuredCount++
        }

        override fun canPostNotifications(): Boolean = notificationsAllowed

        override fun postUpdateAvailableNotification(release: ReleaseInfo): NotificationPostResult {
            if (nextResult is NotificationPostResult.Posted) {
                postedReleases.add(release)
            }
            return nextResult
        }
    }

    private class FakeScheduleBackend : UpdateScheduleBackend {
        data class EnqueueCall(
            val name: String,
            val policy: ExistingPeriodicWorkPolicy,
            val request: PeriodicWorkRequest
        )

        val enqueued = mutableListOf<EnqueueCall>()
        val cancelled = mutableListOf<String>()

        override fun enqueueUniquePeriodic(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest
        ) {
            enqueued.add(EnqueueCall(uniqueWorkName, policy, request))
        }

        override fun cancelUnique(uniqueWorkName: String) {
            cancelled.add(uniqueWorkName)
        }
    }

    private fun fakeHttpClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun jsonResponse(
        request: Request,
        code: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 304) "Not Modified" else "HTTP $code")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun createPrefsRepo(scope: CoroutineScope, name: String): UserPreferencesRepository {
        val file = tempFolder.newFile("$name.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )
        return UserPreferencesRepository(dataStore)
    }

    private fun releaseJson(
        tag: String,
        name: String = "LyricBar $tag",
        body: String = "Changelog for $tag",
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeApk: Boolean = true
    ): String {
        val assetsJson = if (includeApk) {
            """
            [
              {
                "name": "LyricBar-$tag-universal.apk",
                "browser_download_url": "https://github.com/Ishaanj2007/LyricBar/releases/download/$tag/LyricBar-$tag-universal.apk",
                "size": 14000000,
                "content_type": "application/vnd.android.package-archive"
              }
            ]
            """.trimIndent()
        } else {
            "[]"
        }
        return """
        {
          "tag_name": "$tag",
          "name": "$name",
          "published_at": "2026-11-10T12:00:00Z",
          "body": "$body",
          "html_url": "https://github.com/Ishaanj2007/LyricBar/releases/tag/$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "assets": $assetsJson
        }
        """.trimIndent()
    }

    // 1. Version comparison & notification copy formatting
    @Test
    fun versionComparisonAndNotificationMessageFormatting() {
        assertTrue(VersionComparator.isNewer("v2.5.1", "2.5.0"))
        assertTrue(VersionComparator.isNewer("v2.10.0", "2.9.0"))
        assertFalse(VersionComparator.isNewer("v2.5.0", "2.5.0"))
        assertFalse(VersionComparator.isNewer("v2.4.9", "2.5.0"))

        assertEquals(
            "LyricBar v2.5.1 is now available. Tap to see what's new.",
            UpdateNotifier.formatNotificationMessage("v2.5.1")
        )
        assertEquals(
            "LyricBar v2.6.0 is now available. Tap to see what's new.",
            UpdateNotifier.formatNotificationMessage("2.6.0")
        )
    }

    // 2. Ignoring drafts and prereleases while selecting highest stable release
    @Test
    fun ignoresDraftsAndPrereleasesAndSelectsHighestStableRelease() = runBlocking {
        val latestPrerelease = releaseJson(tag = "v2.6.0-beta.1", prerelease = true)
        val allReleases = """
            [
              ${releaseJson(tag = "v2.7.0-draft", draft = true)},
              ${releaseJson(tag = "v2.6.0-rc.1", prerelease = true)},
              ${releaseJson(tag = "v2.5.2", body = "Stable 2.5.2 notes")},
              ${releaseJson(tag = "v2.5.1", body = "Older stable")}
            ]
        """.trimIndent()

        val client = fakeHttpClient { req ->
            when {
                req.url.encodedPath.endsWith("/releases/latest") -> jsonResponse(req, 200, latestPrerelease)
                req.url.encodedPath.endsWith("/releases") -> jsonResponse(req, 200, allReleases)
                else -> jsonResponse(req, 404, "{}")
            }
        }

        val repository = UpdateRepository(client = client)
        val result = repository.fetchLatestStableRelease()

        assertTrue(result is UpdateFetchResult.ReleaseFound)
        val found = result as UpdateFetchResult.ReleaseFound
        assertEquals("v2.5.2", found.release.tagName)
        assertEquals("Stable 2.5.2 notes", found.release.body)
        assertFalse(found.release.isDraft)
        assertFalse(found.release.isPrerelease)
    }

    // 3. Detects newer stable release, uses ETag / 304 cache, and notifies once
    @Test
    fun detectsNewerStableReleaseAndRespectsEtagNotModified() = runBlocking {
        val requestCount = AtomicInteger(0)
        val observedIfNoneMatch = CopyOnWriteArrayList<String?>()
        val releaseBody = releaseJson(tag = "v2.6.0", body = "Automatic update checks release")

        val client = fakeHttpClient { req ->
            val callIdx = requestCount.incrementAndGet()
            observedIfNoneMatch.add(req.header("If-None-Match"))
            if (callIdx == 1) {
                jsonResponse(req, 200, releaseBody, mapOf("ETag" to "\"etag-260\""))
            } else {
                jsonResponse(req, 304, "")
            }
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "etag_and_notify")
            val notifier = FakeNotificationPoster()
            var fakeNow = 10_000L
            val repository = UpdateRepository(client = client)
            val engine = UpdateCheckEngine(
                repository = repository,
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { fakeNow }
            )

            // First automatic worker check
            val firstOutcome = engine.performCheck(UpdateCheckTrigger.WORKER)
            assertTrue(firstOutcome is UpdateCheckOutcome.UpdateAvailable)
            val firstAvailable = firstOutcome as UpdateCheckOutcome.UpdateAvailable
            assertEquals("2.6.0", firstAvailable.release.cleanVersionName)
            assertTrue(firstAvailable.notifiedNow)
            assertEquals(1, notifier.postedReleases.size)

            val savedPrefsAfterFirst = prefsRepo.userPreferencesFlow.first()
            assertEquals("\"etag-260\"", savedPrefsAfterFirst.lastReleaseEtag)
            assertEquals("2.6.0", savedPrefsAfterFirst.lastNotifiedUpdateVersion)
            assertEquals(10_000L, savedPrefsAfterFirst.lastUpdateCheckTimestampMs)

            // Second check well after debounce -> sends If-None-Match and receives 304 without duplicating notification
            fakeNow = 10_000L + UpdateCheckEngine.RECENT_CHECK_DEBOUNCE_MS + 60_000L
            val secondOutcome = engine.performCheck(UpdateCheckTrigger.WORKER)
            assertTrue(secondOutcome is UpdateCheckOutcome.UpdateAvailable)
            val secondAvailable = secondOutcome as UpdateCheckOutcome.UpdateAvailable
            assertEquals("2.6.0", secondAvailable.release.cleanVersionName)
            assertFalse(secondAvailable.notifiedNow)
            assertNull(secondAvailable.notificationResult)
            assertEquals(1, notifier.postedReleases.size)
            assertEquals("\"etag-260\"", observedIfNoneMatch[1])
        } finally {
            dsScope.cancel()
        }
    }

    // 4. Does not notify when already up to date or ahead of remote
    @Test
    fun doesNotNotifyWhenAlreadyOnLatestOrNewerVersion() = runBlocking {
        val client = fakeHttpClient { req ->
            jsonResponse(req, 200, releaseJson(tag = "v2.5.0"))
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "up_to_date")
            val notifier = FakeNotificationPoster()
            val engine = UpdateCheckEngine(
                repository = UpdateRepository(client = client),
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { 55_000L }
            )

            val outcome = engine.performCheck(UpdateCheckTrigger.LAUNCH)
            assertTrue(outcome is UpdateCheckOutcome.UpToDate)
            assertTrue(notifier.postedReleases.isEmpty())
            assertNull(prefsRepo.userPreferencesFlow.first().lastNotifiedUpdateVersion)
            assertEquals(55_000L, prefsRepo.userPreferencesFlow.first().lastUpdateCheckTimestampMs)
        } finally {
            dsScope.cancel()
        }
    }

    // 5 & 6. Deduplication preserves permission-denied retries and notifies again on a newer release
    @Test
    fun deduplicationOnlyRecordsAfterPostSuccessAndNotifiesAgainForNewerRelease() = runBlocking {
        val currentTag = AtomicReference("v2.5.1")
        val client = fakeHttpClient { req ->
            jsonResponse(req, 200, releaseJson(tag = currentTag.get()))
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "dedup_and_newer")
            val notifier = FakeNotificationPoster(nextResult = NotificationPostResult.PermissionDenied)
            var now = 1_000L
            val engine = UpdateCheckEngine(
                repository = UpdateRepository(client = client),
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { now }
            )

            // Step A: Permission denied -> lastNotifiedUpdateVersion remains null so notification isn't suppressed
            val deniedOutcome = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.UpdateAvailable
            assertEquals(NotificationPostResult.PermissionDenied, deniedOutcome.notificationResult)
            assertFalse(deniedOutcome.notifiedNow)
            assertNull(prefsRepo.userPreferencesFlow.first().lastNotifiedUpdateVersion)

            // Step B: Permission granted -> notifies for v2.5.1 and persists it
            notifier.nextResult = NotificationPostResult.Posted
            now += UpdateCheckEngine.RECENT_CHECK_DEBOUNCE_MS + 10_000L
            val postedFirst = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.UpdateAvailable
            assertTrue(postedFirst.notifiedNow)
            assertEquals("2.5.1", prefsRepo.userPreferencesFlow.first().lastNotifiedUpdateVersion)
            assertEquals(1, notifier.postedReleases.size)

            // Step C: Manual check for same v2.5.1 -> deduplicated
            val manualSame = engine.performCheck(UpdateCheckTrigger.MANUAL) as UpdateCheckOutcome.UpdateAvailable
            assertFalse(manualSame.notifiedNow)
            assertEquals(1, notifier.postedReleases.size)

            // Step D: Newer release v2.5.2 published -> notifies again and updates lastNotifiedUpdateVersion
            currentTag.set("v2.5.2")
            now += UpdateCheckEngine.RECENT_CHECK_DEBOUNCE_MS + 10_000L
            val postedSecond = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.UpdateAvailable
            assertTrue(postedSecond.notifiedNow)
            assertEquals("2.5.2", prefsRepo.userPreferencesFlow.first().lastNotifiedUpdateVersion)
            assertEquals(2, notifier.postedReleases.size)
        } finally {
            dsScope.cancel()
        }
    }

    // 7. Disabled automatic checks skip background triggers while manual checks continue working
    @Test
    fun disabledAutomaticChecksSkipBackgroundButAllowManual() = runBlocking {
        val serverHits = AtomicInteger(0)
        val client = fakeHttpClient { req ->
            serverHits.incrementAndGet()
            jsonResponse(req, 200, releaseJson(tag = "v2.5.1"))
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "disabled_auto")
            assertTrue("Auto update checks must default to enabled", prefsRepo.userPreferencesFlow.first().autoUpdateChecksEnabled)
            prefsRepo.setAutoUpdateChecksEnabled(false)

            val notifier = FakeNotificationPoster()
            val engine = UpdateCheckEngine(
                repository = UpdateRepository(client = client),
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { 77_000L }
            )

            assertEquals(UpdateCheckOutcome.SkippedDisabled, engine.performCheck(UpdateCheckTrigger.WORKER))
            assertEquals(UpdateCheckOutcome.SkippedDisabled, engine.performCheck(UpdateCheckTrigger.LAUNCH))
            assertEquals(0, serverHits.get())

            // Manual check still works even when automatic checks are disabled
            val manualOutcome = engine.performCheck(UpdateCheckTrigger.MANUAL)
            assertTrue(manualOutcome is UpdateCheckOutcome.UpdateAvailable)
            assertEquals(1, serverHits.get())
            assertEquals(77_000L, prefsRepo.userPreferencesFlow.first().lastUpdateCheckTimestampMs)
        } finally {
            dsScope.cancel()
        }
    }

    // 8. 48-hour launch window, WorkManager scheduling & cancellation, and Worker result mapping
    @Test
    fun scheduling48HoursLaunchCatchUpAndWorkerRetryMapping() {
        val fortyEightHoursMs = 48L * 60L * 60L * 1000L
        assertEquals(48L, UpdateCheckScheduler.UPDATE_CHECK_INTERVAL_HOURS)
        assertEquals(fortyEightHoursMs, UpdateCheckScheduler.LAUNCH_CHECK_MIN_INTERVAL_MS)

        // Launch check logic
        assertTrue(UpdateCheckScheduler.shouldCheckOnAppLaunch(true, 0L, 1_000L))
        assertFalse(UpdateCheckScheduler.shouldCheckOnAppLaunch(false, 0L, 1_000L))
        assertFalse(UpdateCheckScheduler.shouldCheckOnAppLaunch(true, 1_000L, 1_000L + fortyEightHoursMs - 1L))
        assertTrue(UpdateCheckScheduler.shouldCheckOnAppLaunch(true, 1_000L, 1_000L + fortyEightHoursMs))

        // Periodic schedule sync & cancellation via UpdateScheduleBackend
        val backend = FakeScheduleBackend()
        UpdateCheckScheduler.syncPeriodicSchedule(backend, enabled = true)
        assertEquals(1, backend.enqueued.size)
        val enqueue = backend.enqueued.first()
        assertEquals(UpdateCheckScheduler.UPDATE_CHECK_WORK_NAME, enqueue.name)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, enqueue.policy)
        assertEquals(NetworkType.CONNECTED, enqueue.request.workSpec.constraints.requiredNetworkType)
        assertEquals(fortyEightHoursMs, enqueue.request.workSpec.intervalDuration)

        UpdateCheckScheduler.syncPeriodicSchedule(backend, enabled = false)
        assertEquals(listOf(UpdateCheckScheduler.UPDATE_CHECK_WORK_NAME), backend.cancelled)

        // Worker outcome mapping
        assertEquals(
            ListenableWorker.Result.success(),
            UpdateCheckWorker.mapOutcomeToWorkerResult(UpdateCheckOutcome.UpToDate("2.5.0", 10L), 0)
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            UpdateCheckWorker.mapOutcomeToWorkerResult(UpdateCheckOutcome.Failure("Timeout", isRetryable = true), 0)
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            UpdateCheckWorker.mapOutcomeToWorkerResult(
                UpdateCheckOutcome.Failure("Timeout", isRetryable = true),
                UpdateCheckWorker.MAX_RETRY_ATTEMPTS
            )
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            UpdateCheckWorker.mapOutcomeToWorkerResult(UpdateCheckOutcome.Failure("Malformed JSON", isRetryable = false), 0)
        )
    }

    // 9. Network IOException, rate-limit (429), and malformed JSON failures
    @Test
    fun networkRateLimitAndMalformedJsonErrorsDoNotAdvanceTimestamp() = runBlocking {
        val mode = AtomicReference("rate_limit")
        val client = fakeHttpClient { req ->
            when (mode.get()) {
                "rate_limit" -> jsonResponse(req, 429, "{\"message\":\"API rate limit exceeded\"}")
                "malformed" -> jsonResponse(req, 200, "{not-valid-json")
                "io_error" -> throw IOException("Simulated network timeout")
                else -> jsonResponse(req, 503, "Service Unavailable")
            }
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "errors")
            val notifier = FakeNotificationPoster()
            val engine = UpdateCheckEngine(
                repository = UpdateRepository(client = client),
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { 99_000L }
            )

            // Rate limit -> retryable failure, timestamp untouched
            val rateLimitOutcome = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.Failure
            assertTrue(rateLimitOutcome.isRetryable)
            assertEquals(0L, prefsRepo.userPreferencesFlow.first().lastUpdateCheckTimestampMs)

            // Malformed JSON -> non-retryable failure, timestamp untouched
            mode.set("malformed")
            val malformedOutcome = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.Failure
            assertFalse(malformedOutcome.isRetryable)
            assertEquals(0L, prefsRepo.userPreferencesFlow.first().lastUpdateCheckTimestampMs)

            // Network IOException -> retryable failure, timestamp untouched
            mode.set("io_error")
            val ioOutcome = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.Failure
            assertTrue(ioOutcome.isRetryable)
            assertEquals(0L, prefsRepo.userPreferencesFlow.first().lastUpdateCheckTimestampMs)

            // 503 Server Error -> retryable failure
            mode.set("server_503")
            val serverOutcome = engine.performCheck(UpdateCheckTrigger.WORKER) as UpdateCheckOutcome.Failure
            assertTrue(serverOutcome.isRetryable)
            assertTrue(notifier.postedReleases.isEmpty())
        } finally {
            dsScope.cancel()
        }
    }

    // 10. Manual and automatic checks share UpdateCheckEngine mutex and do not overlap or duplicate notifications
    @Test
    fun concurrentManualAndWorkerChecksAreSerializedAndDeduplicated() = runBlocking {
        val serverCalls = AtomicInteger(0)
        val client = fakeHttpClient { req ->
            serverCalls.incrementAndGet()
            Thread.sleep(150)
            jsonResponse(req, 200, releaseJson(tag = "v2.5.3"))
        }

        val dsScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val prefsRepo = createPrefsRepo(dsScope, "concurrent")
            val notifier = FakeNotificationPoster()
            val engine = UpdateCheckEngine(
                repository = UpdateRepository(client = client),
                preferencesRepository = prefsRepo,
                notifier = notifier,
                installedVersionProvider = { "2.5.0" },
                clockMs = { 500_000L }
            )

            val workerJob = async(Dispatchers.IO) { engine.performCheck(UpdateCheckTrigger.WORKER) }
            val manualJob = async(Dispatchers.IO) { engine.performCheck(UpdateCheckTrigger.MANUAL) }
            val outcomes = awaitAll(workerJob, manualJob)

            assertTrue(outcomes.all { it is UpdateCheckOutcome.UpdateAvailable })
            assertEquals("Only one notification must be posted across overlapping checks", 1, notifier.postedReleases.size)
            assertEquals("2.5.3", prefsRepo.userPreferencesFlow.first().lastNotifiedUpdateVersion)
            assertNotNull(engine.loadCachedAvailableReleaseOrNull())
        } finally {
            dsScope.cancel()
        }
    }
}
