/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.AndroidViewModel
import com.baba.callvault.BuildConfig
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.system.updates.UpdateScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.baba.callvault.system.diagnostics.SystemLogCollector
import com.baba.callvault.utils.AppLogger

// -------- Screen state & action types owned by this ViewModel

/**
 * Interface defining all user actions that can be triggered from the Settings screen.
 * This abstraction allows Compose overloads without concrete ViewModels, allowing Previews of the Stateless UI.
 */
interface SettingsActions {
    /** Re-reads settings-derived state (e.g. the debug log's size after it is cleared). */
    fun refreshSettings()

    /** Master switch for carrier calls; off is the "app calls only" mode. */
    fun setCarrierRecording(enabled: Boolean)
    fun setAutoRecordIncoming(enabled: Boolean)
    fun setAutoRecordOutgoing(enabled: Boolean)
    fun setVibrationEnabled(enabled: Boolean)
    fun setIgnoreAnonymousIncoming(enabled: Boolean)
    fun setIgnoreCrossCountryIncoming(enabled: Boolean)
    fun setIgnoreCrossCountryOutgoing(enabled: Boolean)
    fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode)
    fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode)
    fun setAudioSource(source: String)
    fun setAudioCodec(codec: String)
    fun setAudioBitRate(bitRate: Int)
    fun setThemeMode(mode: AppPreferences.ThemeMode)
    fun setDynamicColorEnabled(enabled: Boolean)
    fun setShowToastsEnabled(enabled: Boolean)
    fun setAppLanguage(languageCode: String)
    fun setLoggingEnabled(enabled: Boolean)
    fun getAppVersion(): String
    fun setFileNameTemplate(template: String)
    fun setStorageTarget(target: StorageTarget)
    fun setDriveFolderUri(uri: android.net.Uri?)
    fun setRetentionLinked(linked: Boolean)
    fun setRetentionLocalDays(days: Int)
    fun setRetentionDriveDays(days: Int)
    fun setRetentionTimeHour(hour: Int)
    fun setRetentionTimeMinute(minute: Int)
    fun setSyncScheduleMode(mode: SyncScheduleMode)
    fun setSyncTimeHour(hour: Int)
    fun setSyncTimeMinute(minute: Int)
    fun setSyncDayOfWeek(day: Int)
    fun setUpdateCheckEnabled(enabled: Boolean)
}

/**
 * The "Brain" of the Settings screen.
 *
 * Navigation and onboarding routing are handled by [AppNavigationViewModel].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application), SettingsActions {

    /**
     * Application context — safe to store in a ViewModel because it lives as long as the app
     * process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /**
     * Read and Manager AppPreference settings
     */
    val preferences = AppPreferences(appContext)

    // -------- Internal mutable state
    // Private so only this ViewModel can mutate it.

    /**
     * Backing store for [updateTrigger].
     */
    private val _updateTrigger = MutableStateFlow(0)

    // -------- Public state

    /**
     * A trigger flow for recomposition.
     */
    val updateTrigger: StateFlow<Int> = _updateTrigger.asStateFlow()

    // -------- Refresh

    /**
     * Retrieves the formatted application version string, including CI run numbers.
     *
     * @return Formatted string like "Version 1.0 (1) - CI Run #1234" or "Version 1.0 (1)"
     */
    override fun getAppVersion(): String {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val base = "Version ${packageInfo.versionName} (${packageInfo.longVersionCode})"
            val ciBuild = BuildConfig.CI_BUILD_NUMBER
            if (ciBuild.lowercase() == "local") {
                "$base - Local Build"
            } else {
                "$base - CI Run #$ciBuild"
            }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "Unknown Version"
        }
    }

    /**
     * Triggers a recompose across the settings screen.
     * 
     * **Important:** Jetpack Compose compiler is aggressive when optimizing and will skip
     * recomposition of components if it thinks inputs haven't changed (Dead Parameter Elimination).
     * Since [preferences] reads are not backed by Compose `State`, you must wrap your reads in 
     * `remember(updateTrigger)` in your composables so the compiler knows they must be re-evaluated.
     * 
     * Example:
     * ```kotlin
     * val updateTrigger by viewModel.updateTrigger.collectAsState()
     * val autoRecord = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
     * ```
     */
    fun refresh() {
        _updateTrigger.update { it + 1 }
    }

    // -------- Recording settings

    /** Re-reads settings-derived state, e.g. the debug log's size after it has been cleared. */
    override fun refreshSettings() = refresh()

    /** Handle carrier calls at all, or ignore them entirely ("app calls only").
     *
     * @param enabled `false` to ignore phone calls — no recording and no standby prompt.
     */
    override fun setCarrierRecording(enabled: Boolean) {
        preferences.setCarrierRecordingEnabled(enabled)
        refresh()
    }

    /** Turn automatic recording of incoming calls on or off.
     *
     * @param enabled `true` to record incoming calls automatically.
     */
    override fun setAutoRecordIncoming(enabled: Boolean) {
        preferences.setAutoRecordIncomingEnabled(enabled)
        refresh()
    }

    /** Turn automatic recording of outgoing calls on or off.
     *
     * @param enabled `true` to record outgoing calls automatically.
     */
    override fun setAutoRecordOutgoing(enabled: Boolean) {
        preferences.setAutoRecordOutgoingEnabled(enabled)
        refresh()
    }

    /** Enables or disables vibration feedback.
     *
     * @param enabled `true` to vibrate on start/stop.
     */
    override fun setVibrationEnabled(enabled: Boolean) {
        preferences.setVibrationEnabled(enabled)
        refresh()
    }

    /** When enabled, anonymous calls (no caller ID) are not recorded automatically.
     *
     * @param enabled `true` to skip recording calls with no caller ID.
     */
    override fun setIgnoreAnonymousIncoming(enabled: Boolean) {
        preferences.setIgnoreAnonymousIncomingEnabled(enabled)
        // When we disable anonymous ignore, we automatically disable cross country ignore because both a related. Anonymous call may as well be cross-country.
        if (!enabled) preferences.setIgnoreCrossCountryIncomingEnabled(false)
        refresh()
    }

    /**
     * Sets whether to ignore incoming cross-country calls.
     */
    override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {
        preferences.setIgnoreCrossCountryIncomingEnabled(enabled)
        refresh()
    }

    /**
     * Sets whether to ignore outgoing cross-country calls.
     */
    override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {
        preferences.setIgnoreCrossCountryOutgoingEnabled(enabled)
        refresh()
    }

    /**
     * Sets which incoming contacts to ignore.
     *
     * @param modeEnum The [AppPreferences.IgnoreContactsMode] enum value to set.
     */
    override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {
        preferences.setIgnoreContactsModeIncoming(modeEnum)
        refresh()
    }

    /**
     * Sets which outgoing contacts to ignore.
     *
     * @param modeEnum The [AppPreferences.IgnoreContactsMode] enum value to set.
     */
    override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {
        preferences.setIgnoreContactsModeOutgoing(modeEnum)
        refresh()
    }

    /** Saves the audio source to use for recording (e.g. "mic-voice-communication").
     *
     * @param source The audio source key passed to scrcpy's `audio_source` parameter.
     */
    override fun setAudioSource(source: String) {
        preferences.setAudioSource(source)
        refresh()
    }

    /** Saves the audio codec to use ("opus" or "aac").
     *
     * @param codec The codec key string.
     */
    override fun setAudioCodec(codec: String) {
        preferences.setAudioCodec(codec)
        ScrcpyAudioCodec.fromKey(codec).let {
            // Automatically adjust the bitrate to recommended value when codec changes
            preferences.setAudioBitRate(it.defaultBitRate)
        }
        refresh()
    }

    /** Saves the audio bit rate in bits per second (e.g. 16000 = 16 kbps).
     *
     * @param bitRate The bit rate in bps.
     */
    override fun setAudioBitRate(bitRate: Int) {
        preferences.setAudioBitRate(bitRate)
        refresh()
    }

    // -------- File Naming --------

    /** Saves the file name template.
     *
     * @param template The template string.
     */
    override fun setFileNameTemplate(template: String) {
        preferences.setFileNameTemplate(template)
        refresh()
    }

    // -------- Storage settings

    /** Saves the storage target (local, Drive, or both).
     *
     * @param target The [StorageTarget] enum value.
     */
    override fun setStorageTarget(target: StorageTarget) {
        preferences.setStorageTarget(target)
        refresh()
    }

    /** Saves the Google Drive folder URI chosen via SAF.
     *
     * @param uri The URI returned by the SAF tree picker, or null to clear.
     */
    override fun setDriveFolderUri(uri: android.net.Uri?) {
        preferences.setDriveFolderUri(uri)
        refresh()
    }

    // -------- Retention settings

    /** Saves whether device & Drive share one retention period. */
    override fun setRetentionLinked(linked: Boolean) {
        preferences.setRetentionLinked(linked)
        refresh()
    }

    /** Saves the on-device retention (days; 0 = keep forever) and reconciles the periodic sweep. */
    override fun setRetentionLocalDays(days: Int) {
        preferences.setRetentionLocalDays(days)
        RetentionScheduler.apply(appContext)
        refresh()
    }

    /** Saves the Drive retention (days; 0 = keep forever) and reconciles the periodic sweep. */
    override fun setRetentionDriveDays(days: Int) {
        preferences.setRetentionDriveDays(days)
        RetentionScheduler.apply(appContext)
        refresh()
    }

    /** Saves the retention sweep hour (0-23, local) and re-anchors the periodic sweep. */
    override fun setRetentionTimeHour(hour: Int) {
        preferences.setRetentionTimeHour(hour)
        RetentionScheduler.apply(appContext)
        refresh()
    }

    /**
     * Saves when recordings are uploaded to Drive and reconciles the periodic sweep.
     *
     * [SyncScheduler.apply] must run on every change: it is what registers (or cancels) the periodic
     * work, and `StorageRouter.route` reads the mode on each finished call to decide between copying
     * immediately and leaving the file for that sweep. Persisting the preference without reconciling
     * would leave the two disagreeing — recordings held back for a sweep that was never scheduled.
     */
    override fun setSyncScheduleMode(mode: SyncScheduleMode) {
        preferences.setSyncScheduleMode(mode)
        SyncScheduler.apply(appContext)
        refresh()
    }

    /** Saves the scheduled-upload hour (0-23, local) and re-anchors the periodic sweep. */
    override fun setSyncTimeHour(hour: Int) {
        preferences.setSyncTimeHour(hour)
        SyncScheduler.apply(appContext)
        refresh()
    }

    /** Saves the scheduled-upload minute and re-anchors the periodic sweep. */
    override fun setSyncTimeMinute(minute: Int) {
        preferences.setSyncTimeMinute(minute)
        SyncScheduler.apply(appContext)
        refresh()
    }

    /** Saves the weekly upload day (Calendar.SUNDAY=1 .. SATURDAY=7) and re-anchors the periodic sweep. */
    override fun setSyncDayOfWeek(day: Int) {
        preferences.setSyncDayOfWeek(day)
        SyncScheduler.apply(appContext)
        refresh()
    }

    /** Saves the retention sweep minute (0-59) and re-anchors the periodic sweep. */
    override fun setRetentionTimeMinute(minute: Int) {
        preferences.setRetentionTimeMinute(minute)
        RetentionScheduler.apply(appContext)
        refresh()
    }

    // -------- In-app updates

    /** Turn the daily update check on or off (also schedules/cancels the periodic worker). */
    override fun setUpdateCheckEnabled(enabled: Boolean) {
        preferences.setUpdateCheckEnabled(enabled)
        if (!enabled) preferences.setAvailableUpdateTag(null)
        UpdateScheduler.apply(appContext)
        refresh()
    }

    // -------- Visual settings

    /** Saves the app theme.
     *
     * @param mode The ThemeMode enum value.
     */
    override fun setThemeMode(mode: AppPreferences.ThemeMode) {
        preferences.setThemeMode(mode)
        refresh()
    }

    /** Enables or disables Material You colours extracted from the wallpaper.
     *
     * @param enabled `true` to use wallpaper-derived colours; `false` to use the static palette.
     */
    override fun setDynamicColorEnabled(enabled: Boolean) {
        preferences.setDynamicColorEnabled(enabled)
        refresh()
    }

    /** Enables or disables toast notifications.
     *
     * @param enabled `true` to show toast notifications; `false` to disable them.
     */
    override fun setShowToastsEnabled(enabled: Boolean) {
        preferences.setShowToastsEnabled(enabled)
        refresh()
    }

    /** Saves the app language using AppCompat.
     *
     * @param languageCode The BCP-47 language tag describing the locale, or empty to follow system setting.
     */
    override fun setAppLanguage(languageCode: String) {
        val localeList = if (languageCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
        refresh()
    }

    // -------- Debug settings

    /** Turns diagnostic logging on or off.
     *
     * Turning it **on** clears any previous capture so each bug report is a fresh, focused log.
     * Turning it **off** keeps the captured log on disk so the user can still share it afterwards.
     * Either way the persistent "debug logging is on" reminder is posted/cleared to match.
     *
     * @param enabled `true` to start capturing application logs.
     */
    override fun setLoggingEnabled(enabled: Boolean) {
        if (enabled) {
            AppLogger.clearLogs()
        }
        preferences.setLoggingEnabled(enabled)
        DebugNotificationHelper.sync(appContext)
        refresh()

        // Grow logcat's ring while logging runs, and put it back afterwards. The daemon logs nowhere
        // else, and the default 256 KiB buffer holds about a minute on a busy phone — long enough to
        // lose the evidence between reproducing a bug and reaching this screen to share it.
        //
        // Deliberately after the preference is written and the UI has refreshed: this talks to the
        // shell, and the toggle must never wait on it or fail because of it.
        viewModelScope.launch {
            runCatching {
                if (enabled) SystemLogCollector.onLoggingEnabled(appContext)
                else SystemLogCollector.onLoggingDisabled(appContext)
            }.onFailure { AppLogger.w("CV:Settings", "Logcat ring adjustment failed: ${it.message}") }
        }
    }
}
