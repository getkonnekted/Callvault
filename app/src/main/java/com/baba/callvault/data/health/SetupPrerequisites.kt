/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.server.RecorderConnection

/** A user-owned condition that must hold for a call to be recordable at all. */
enum class Prerequisite { RECORDING_FOLDER, ADB_PAIRING, DEVELOPER_OPTIONS, SECURE_SETTINGS_GRANT }

/**
 * The single definition of "is this setup capable of recording right now", shared by
 * [com.baba.callvault.ui.viewmodels.HomeViewModel.computeStatus] (which maps the result onto its
 * `HomeStatus` enum for the card) and [com.baba.callvault.services.call.CallSessionManager] (which
 * uses it to report a call precisely instead of leaving it to the gap sweep to infer later).
 *
 * Every condition here is USER-OWNED: a missing recording folder, an ADB pairing never completed, the
 * Developer options master toggle off, or the WRITE_SECURE_SETTINGS grant gone while there is no live
 * daemon to fall back on. These are deliberate or environmental facts, not a symptom of the daemon
 * failing on its own — that distinction is why a call in a window where [missing] is non-null EXCUSES
 * the call, while a live daemon dying with every prerequisite intact must never be excused by this
 * check. Daemon liveness ([RecorderConnection.isConnected]) is intentionally examined only as a
 * fallback for the last condition, exactly as `computeStatus()` already did — never as a check of its
 * own, and never in a way that hides a daemon that died on its own.
 */
object SetupPrerequisites {

    /**
     * The first missing prerequisite, in the same precedence
     * [com.baba.callvault.ui.viewmodels.HomeViewModel.computeStatus] checks them, or null when all
     * are met. Synchronous, cheap reads only — no I/O, no daemon launch.
     */
    fun missing(context: Context): Prerequisite? {
        val preferences = AppPreferences(context)
        if (preferences.getRecordingFolderUri() == null) return Prerequisite.RECORDING_FOLDER
        if (!preferences.isAdbPaired()) return Prerequisite.ADB_PAIRING
        // isExplicitlyDisabled (not !isEnabled): an absent/unreadable global must not read as missing
        // on a ROM that doesn't expose the setting — see DeveloperOptions' own doc comment.
        if (DeveloperOptions.isExplicitlyDisabled(context)) return Prerequisite.DEVELOPER_OPTIONS
        // The grant is only needed to relaunch a DEAD daemon; while one is already connected, recording
        // works right now regardless of the grant, so this only counts as missing when BOTH are true.
        if (!AdbShell.hasWriteSecureSettings(context) && !RecorderConnection.isConnected) {
            return Prerequisite.SECURE_SETTINGS_GRANT
        }
        return null
    }
}

/**
 * Reports, for the status card, that the call starting at [atMillis] could not have been recorded
 * because [missing] was absent — precise reporting (naming the exact cause via
 * [SetupHealthStore.recordMissedWhileNotReady]) instead of leaving it to the gap sweep to later infer
 * an unexplained [SetupHealth.CallNotRecorded] for a call that was never recordable in the first place.
 *
 * Two refusals matter as much as the recording itself:
 *  - A no-op when [missing] is null: a daemon that dies later on an otherwise-complete setup is the
 *    normal path this whole feature exists to catch, and must stay reportable, so nothing is ever
 *    recorded here on that path.
 *  - GATED on the setup having verified at least once before ([HealthFacts.lastVerifiedAt] > 0). A user
 *    who has never had a single working call — mid-onboarding, no folder configured yet, never paired —
 *    must never be told a call was "missed"; there is nothing earlier that proved recording ever worked
 *    for them to have lost. Without this gate, reporting turns into nagging someone who never finished
 *    setting the app up.
 */
fun SetupHealthStore.recordMissedForMissingPrerequisite(missing: Prerequisite?, atMillis: Long, label: String?) {
    if (missing == null) return
    if (read().lastVerifiedAt <= 0L) return
    recordMissedWhileNotReady(atMillis, label, missing)
}
