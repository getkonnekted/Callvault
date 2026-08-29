/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.DriveCatalogRepair
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.UntrackedRecordings
import com.baba.callvault.utils.AppLogger

/**
 * Daily sweep that permanently deletes recordings older than the configured retention period. Reads the
 * Room catalog (the source of truth for the Home list) and applies retention PER COPY: device copies use
 * [AppPreferences.getRetentionLocalDays], Drive copies use [AppPreferences.getRetentionDriveDays] (0 =
 * keep forever). Age is measured from each entry's recorded timestamp ([lastModified]).
 *
 * Deletion goes through [RecordingsRepository.deleteFile], which removes the SAF file and clears that copy
 * from the catalog ONLY once the file is actually gone (dropping the row when no copy remains). Robust by
 * design: entries with an unknown age are never deleted; a failed delete (e.g. Drive offline) keeps its
 * catalog entry and is retried on the next daily run.
 *
 * **It also sweeps what the catalog has lost.** Walking the catalog alone made a bookkeeping gap into a
 * permanent exemption: a file we had no entry for was never considered, so it sat in the folder for ever
 * regardless of its age, and one device had accumulated 131 of them. The setting promises a daily check of
 * the recording folders, so the folders are what gets checked — see [UntrackedRecordings], which also
 * explains why only names CallVault itself writes are eligible.
 */
class RetentionSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val localDays = prefs.getRetentionLocalDays()
        val driveDays = prefs.getRetentionDriveDays()
        if (localDays <= 0 && driveDays <= 0) {
            AppLogger.i(TAG, "Retention off; nothing to sweep.")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val localCutoff = RetentionPolicy.cutoffFor(localDays, now)
        val driveCutoff = RetentionPolicy.cutoffFor(driveDays, now)

        // A Drive reference minted under a grant we no longer hold can be neither used nor noticed —
        // the catalog pass fails on it for ever while the untracked pass skips the file as already
        // known. Repair those first, so both halves below see a catalog that matches reality.
        DriveCatalogRepair.reconcile(applicationContext)

        var deletedLocal = 0
        var deletedDrive = 0
        for (entry in RecordingCatalog.all(applicationContext)) {
            val ts = entry.lastModified
            val localUri = entry.localUri
            if (localUri != null && RetentionPolicy.isExpired(ts, localCutoff)) {
                if (RecordingsRepository.deleteFile(applicationContext, localUri.toUri())) deletedLocal++
            }
            val driveUri = entry.driveUri
            if (driveUri != null && RetentionPolicy.isExpired(ts, driveCutoff)) {
                if (RecordingsRepository.deleteFile(applicationContext, driveUri.toUri())) deletedDrive++
            }
        }

        // The folders, not just our record of them. A recording the catalog has lost is exactly as old as
        // one it remembers, and the setting promises a daily check of the folder — so a bookkeeping gap
        // must not quietly become an exemption that lasts forever.
        val untracked = UntrackedRecordings.find(applicationContext)
        deletedLocal += deleteExpired(untracked.device, localCutoff, "device")
        deletedDrive += deleteExpired(untracked.drive, driveCutoff, "Drive")

        AppLogger.i(TAG, "Retention sweep complete (deletedLocal=$deletedLocal deletedDrive=$deletedDrive).")
        return Result.success()
    }

    /**
     * Deletes the untracked copies that are past [cutoff], straight through SAF.
     *
     * No catalog bookkeeping, because there is no entry to keep — which also makes this the one part of
     * the sweep that retries itself for free: the folder is re-enumerated every run, so a copy that fails
     * to delete today is simply found again tomorrow.
     */
    private fun deleteExpired(items: List<RecordingItem>, cutoff: Long?, where: String): Int {
        var deleted = 0
        for (item in items) {
            if (!RetentionPolicy.isExpired(item.lastModified, cutoff)) continue
            val gone = runCatching {
                DocumentFile.fromSingleUri(applicationContext, item.uri)?.delete() == true
            }.getOrDefault(false)
            if (gone) deleted++ else AppLogger.w(TAG, "Could not delete untracked $where copy '${item.displayName}'")
        }
        if (deleted > 0) AppLogger.i(TAG, "Deleted $deleted untracked $where recording(s) past the retention period")
        return deleted
    }

    companion object {
        private const val TAG = "CV:RetentionSweep"
    }
}
