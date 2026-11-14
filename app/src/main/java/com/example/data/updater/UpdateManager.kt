package com.example.data.updater

import android.content.Context
import android.content.Intent
import com.example.BuildConfig
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    data class UpToDate(val installedVersion: String, val checkedAt: Long) : UpdateUiState
    data class Available(
        val release: ReleaseInfo,
        val installedVersion: String
    ) : UpdateUiState
    data class Downloading(
        val release: ReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : UpdateUiState
    data class DownloadFailed(
        val release: ReleaseInfo,
        val reason: String
    ) : UpdateUiState
    data class Verifying(
        val release: ReleaseInfo,
        val apkFile: File
    ) : UpdateUiState
    data class VerificationFailed(
        val release: ReleaseInfo,
        val reason: String
    ) : UpdateUiState
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFile: File,
        val checksumStatus: ChecksumStatus,
        val certificateFingerprint: String
    ) : UpdateUiState
    data class PermissionRequired(
        val release: ReleaseInfo,
        val apkFile: File,
        val settingsIntent: Intent
    ) : UpdateUiState
    data class InstallationLaunched(val release: ReleaseInfo) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class UpdateManager private constructor(
    private val context: Context,
    private val repository: UpdateRepository = UpdateRepository(),
    private val downloader: ApkDownloader = ApkDownloader(context)
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val preferencesRepository = UserPreferencesRepository(context.applicationContext)
    private val notifier = UpdateNotifier(context.applicationContext)
    private val checkEngine = UpdateCheckEngine(
        repository = repository,
        preferencesRepository = preferencesRepository,
        notifier = notifier,
        installedVersionProvider = { installedVersionName }
    )

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _cachedAvailableRelease = MutableStateFlow<ReleaseInfo?>(null)
    val cachedAvailableRelease: StateFlow<ReleaseInfo?> = _cachedAvailableRelease.asStateFlow()

    val installedVersionName: String
        get() = BuildConfig.VERSION_NAME

    val installedVersionCode: Long
        get() = BuildConfig.VERSION_CODE.toLong()

    init {
        scope.launch(Dispatchers.IO) {
            _cachedAvailableRelease.value = checkEngine.loadCachedAvailableReleaseOrNull()
        }

        // Observe downloader progress and translate to UpdateUiState
        scope.launch {
            downloader.downloadState.collect { downloadState ->
                val currentState = _uiState.value
                val activeRelease = when (currentState) {
                    is UpdateUiState.Downloading -> currentState.release
                    is UpdateUiState.DownloadFailed -> currentState.release
                    is UpdateUiState.Available -> currentState.release
                    else -> null
                } ?: return@collect

                when (downloadState) {
                    is DownloadState.Progress -> {
                        _uiState.value = UpdateUiState.Downloading(
                            release = activeRelease,
                            bytesDownloaded = downloadState.bytesDownloaded,
                            totalBytes = downloadState.totalBytes,
                            progressFraction = downloadState.progressFraction
                        )
                    }
                    is DownloadState.Completed -> {
                        verifyApkFile(activeRelease, downloadState.apkFile)
                    }
                    is DownloadState.Failed -> {
                        _uiState.value = UpdateUiState.DownloadFailed(
                            release = activeRelease,
                            reason = downloadState.reason
                        )
                    }
                    is DownloadState.Cancelled -> {
                        _uiState.value = UpdateUiState.Available(
                            release = activeRelease,
                            installedVersion = installedVersionName
                        )
                    }
                    DownloadState.Idle -> {
                        // No-op
                    }
                }
            }
        }
    }

    fun checkForUpdates(isUserInitiated: Boolean = true) {
        val current = _uiState.value
        if (
            current is UpdateUiState.Checking ||
            current is UpdateUiState.Downloading ||
            current is UpdateUiState.Verifying ||
            current is UpdateUiState.ReadyToInstall ||
            current is UpdateUiState.PermissionRequired
        ) {
            return
        }

        _uiState.value = UpdateUiState.Checking

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                checkEngine.performCheck(
                    trigger = UpdateCheckTrigger.MANUAL,
                    allowRecentSkip = !isUserInitiated
                )
            }
            applyManualCheckOutcome(outcome)
        }
    }

    suspend fun performBackgroundUpdateCheck(
        trigger: UpdateCheckTrigger = UpdateCheckTrigger.WORKER
    ): UpdateCheckOutcome {
        val outcome = withContext(Dispatchers.IO) {
            checkEngine.performCheck(
                trigger = trigger,
                allowRecentSkip = true
            )
        }
        when (outcome) {
            is UpdateCheckOutcome.UpdateAvailable -> _cachedAvailableRelease.value = outcome.release
            is UpdateCheckOutcome.UpToDate,
            is UpdateCheckOutcome.NoStableRelease -> _cachedAvailableRelease.value = null
            is UpdateCheckOutcome.Failure,
            UpdateCheckOutcome.SkippedDisabled,
            UpdateCheckOutcome.SkippedRecent -> Unit
        }
        return outcome
    }

    fun openUpdaterFromNotification() {
        val current = _uiState.value
        if (
            current is UpdateUiState.Available ||
            current is UpdateUiState.Downloading ||
            current is UpdateUiState.DownloadFailed ||
            current is UpdateUiState.Verifying ||
            current is UpdateUiState.VerificationFailed ||
            current is UpdateUiState.PermissionRequired ||
            current is UpdateUiState.ReadyToInstall ||
            current is UpdateUiState.InstallationLaunched ||
            current is UpdateUiState.Checking
        ) {
            return
        }

        val inMemoryCached = _cachedAvailableRelease.value
        if (
            inMemoryCached != null &&
            inMemoryCached.apkAsset != null &&
            VersionComparator.isNewer(inMemoryCached.tagName, installedVersionName)
        ) {
            _uiState.value = UpdateUiState.Available(
                release = inMemoryCached,
                installedVersion = installedVersionName
            )
            return
        }

        _uiState.value = UpdateUiState.Checking
        scope.launch {
            val diskCached = withContext(Dispatchers.IO) {
                checkEngine.loadCachedAvailableReleaseOrNull()
            }
            if (
                diskCached != null &&
                diskCached.apkAsset != null &&
                VersionComparator.isNewer(diskCached.tagName, installedVersionName)
            ) {
                _cachedAvailableRelease.value = diskCached
                _uiState.value = UpdateUiState.Available(
                    release = diskCached,
                    installedVersion = installedVersionName
                )
            } else {
                val outcome = withContext(Dispatchers.IO) {
                    checkEngine.performCheck(
                        trigger = UpdateCheckTrigger.MANUAL,
                        allowRecentSkip = false
                    )
                }
                applyManualCheckOutcome(outcome)
            }
        }
    }

    private fun applyManualCheckOutcome(outcome: UpdateCheckOutcome) {
        when (outcome) {
            is UpdateCheckOutcome.UpdateAvailable -> {
                _cachedAvailableRelease.value = outcome.release
                _uiState.value = UpdateUiState.Available(
                    release = outcome.release,
                    installedVersion = outcome.installedVersion
                )
            }
            is UpdateCheckOutcome.UpToDate -> {
                _cachedAvailableRelease.value = null
                _uiState.value = UpdateUiState.UpToDate(
                    installedVersion = outcome.installedVersion,
                    checkedAt = outcome.checkedAtMs
                )
            }
            is UpdateCheckOutcome.NoStableRelease -> {
                _cachedAvailableRelease.value = null
                _uiState.value = UpdateUiState.Error(outcome.reason)
            }
            is UpdateCheckOutcome.Failure -> {
                _uiState.value = UpdateUiState.Error(outcome.message)
            }
            UpdateCheckOutcome.SkippedDisabled,
            UpdateCheckOutcome.SkippedRecent -> {
                _uiState.value = UpdateUiState.Idle
            }
        }
    }

    fun startDownload(release: ReleaseInfo) {
        val asset = release.apkAsset
        if (asset == null) {
            _uiState.value = UpdateUiState.Error("No APK download available for this release.")
            return
        }
        downloader.startDownload(asset, scope)
    }

    fun cancelDownload() {
        downloader.cancelDownload()
    }

    fun retryDownload(release: ReleaseInfo) {
        startDownload(release)
    }

    private fun verifyApkFile(release: ReleaseInfo, file: File) {
        _uiState.value = UpdateUiState.Verifying(release, file)

        scope.launch(Dispatchers.Default) {
            // 1. Resolve asset-specific checksum
            var expectedSha256 = release.getChecksumForAsset(release.apkAsset)

            // 2. If not found in metadata or release body, check companion checksum file
            if (expectedSha256 == null && release.apkAsset != null) {
                val companion = release.allAssets.firstOrNull {
                    it.isChecksumFile && (
                        it.name.startsWith(release.apkAsset.name, ignoreCase = true) ||
                        it.name.equals("SHA256SUMS", ignoreCase = true) ||
                        it.name.equals("checksums.txt", ignoreCase = true)
                    )
                }
                if (companion != null) {
                    expectedSha256 = repository.fetchCompanionChecksum(companion, release.apkAsset.name)
                }
            }

            val result = ApkVerifier.verify(
                context = context,
                apkFile = file,
                expectedSha256 = expectedSha256,
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode
            )

            withContext(Dispatchers.Main) {
                when (result) {
                    is VerificationResult.Success -> {
                        // Check if unknown app install permission is required
                        if (!UpdateInstaller.canRequestPackageInstalls(context)) {
                            val settingsIntent = UpdateInstaller.createManageUnknownAppSourcesIntent(context)
                            _uiState.value = UpdateUiState.PermissionRequired(
                                release = release,
                                apkFile = file,
                                settingsIntent = settingsIntent
                            )
                        } else {
                            _uiState.value = UpdateUiState.ReadyToInstall(
                                release = release,
                                apkFile = file,
                                checksumStatus = result.checksumStatus,
                                certificateFingerprint = result.certificateFingerprint
                            )
                        }
                    }
                    is VerificationResult.Error -> {
                        // Securely remove the failed/untrusted APK file immediately
                        try {
                            if (file.exists()) file.delete()
                        } catch (_: Exception) {}

                        _uiState.value = UpdateUiState.VerificationFailed(
                            release = release,
                            reason = result.message
                        )
                    }
                }
            }
        }
    }

    fun installApk(release: ReleaseInfo, file: File) {
        val currentState = _uiState.value
        // Only permit installation when in ReadyToInstall or PermissionRequired states
        if (currentState !is UpdateUiState.ReadyToInstall && currentState !is UpdateUiState.PermissionRequired) {
            _uiState.value = UpdateUiState.Error("Installation cannot proceed: APK has not passed verification.")
            return
        }

        val installResult = UpdateInstaller.installApk(context, file)
        when (installResult) {
            is InstallationResult.Launched -> {
                _uiState.value = UpdateUiState.InstallationLaunched(release)
            }
            is InstallationResult.PermissionRequired -> {
                _uiState.value = UpdateUiState.PermissionRequired(
                    release = release,
                    apkFile = file,
                    settingsIntent = installResult.settingsIntent
                )
            }
            is InstallationResult.Failure -> {
                _uiState.value = UpdateUiState.Error(installResult.message)
            }
        }
    }

    fun resumeAfterPermissionGranted(release: ReleaseInfo, file: File) {
        if (UpdateInstaller.canRequestPackageInstalls(context)) {
            installApk(release, file)
        }
    }

    fun dismiss() {
        if (_uiState.value is UpdateUiState.Downloading) {
            downloader.cancelDownload()
        }
        downloader.reset()
        _uiState.value = UpdateUiState.Idle
    }

    companion object {
        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
