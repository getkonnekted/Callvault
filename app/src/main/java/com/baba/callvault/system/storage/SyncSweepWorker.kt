/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger

/**
 * Batch/scheduled equivalent of [RecordingCopyWorker]. Walks the device recording folder and copies
 * every audio file that is not yet present (by display name) in the Drive folder, using the same
 * [SafHelper.copyFileToFolder] mechanism. After a successful copy it deletes the local source when the
 * storage target is [StorageTarget.DRIVE] (DRIVE = cloud only); [StorageTarget.BOTH] keeps the local copy.
 *
 * Robust by design: no-op when folders are unconfigured or the target is LOCAL; individual file errors
 * are logged and skipped; a copy failure yields [Result.retry] so WorkManager backoff re-runs the sweep.
 */
class SyncSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) {
            AppLogger.i(TAG, "Storage target is LOCAL; nothing to sweep.")
            return Result.success()
        }

        val sourceFolderUri = prefs.getRecordingFolderUri()
        val driveFolderUri = prefs.getDriveFolderUri()
        if (sourceFolderUri == null || driveFolderUri == null) {
            AppLogger.w(TAG, "Sweep skipped: source or Drive folder not configured (source=$sourceFolderUri drive=$driveFolderUri).")
            return Result.success()
        }
        if (!SafHelper.isFolderValid(applicationContext, driveFolderUri)) {
            AppLogger.w(TAG, "Drive folder not currently valid/writable; retrying later.")
            return Result.retry()
        }

        val sourceDir = DocumentFile.fromTreeUri(applicationContext, sourceFolderUri)
        if (sourceDir == null || !sourceDir.exists()) {
            AppLogger.w(TAG, "Source recording folder inaccessible; nothing to sweep.")
            return Result.success()
        }
        val driveDir = DocumentFile.fromTreeUri(applicationContext, driveFolderUri) ?: return Result.retry()

        // What Drive already holds (name -> byte length, case-insensitive), so a recording that is
        // already up there is never uploaded a second time.
        val existingInDrive = driveDir.listFiles()
            .mapNotNull { doc -> doc.name?.lowercase()?.to(runCatching { doc.length() }.getOrDefault(-1L)) }
            .toMap(HashMap())

        val deleteLocal = target == StorageTarget.DRIVE
        var copied = 0
        var failures = 0
        var skipped = 0
        for (file in sourceDir.listFiles()) {
            val name = file.name ?: continue
            if (!file.isFile) continue
            // A staging document from an interrupted copy is not a recording.
            if (CloudCopyPolicy.isStagingName(name)) continue

            val sourceSize = runCatching { file.length() }.getOrDefault(-1L)
            if (sourceSize <= 0L) {
                skipped++
                continue // an empty file means capture never ran; copying it would fake a backup
            }
            val alreadyThere = existingInDrive[name.lowercase()]
            if (alreadyThere != null &&
                CloudCopyPolicy.verdict(alreadyThere, sourceSize) == ExistingCopyVerdict.COMPLETE
            ) {
                continue
            }

            val mime = file.type ?: DEFAULT_MIME
            val driveUri = when (val result = SafHelper.copyFileToFolder(applicationContext, file.uri, driveFolderUri, name, mime, sourceSize)) {
                is SafHelper.CopyResult.AlreadyPresent -> result.uri
                is SafHelper.CopyResult.Copied -> result.uri.also { copied++ }
                is SafHelper.CopyResult.Failed -> {
                    failures++
                    AppLogger.w(TAG, "Failed to copy '$name' to Drive (${result.reason}).")
                    null
                }
            } ?: continue

            existingInDrive[name.lowercase()] = sourceSize
            if (deleteLocal) SafHelper.deleteDocument(file, "the device copy of '$name'")
            // Stamp the Drive copy onto the catalog (clearing the local copy for DRIVE-only mode) so
            // the Home list reflects the swept file without re-scanning the Drive folder.
            RecordingCatalog.markDrive(applicationContext, name, driveUri, sourceSize, deleteLocal)
        }

        AppLogger.i(TAG, "Sweep complete (target=$target copied=$copied failures=$failures skipped=$skipped deleteLocal=$deleteLocal).")
        // A sweep that keeps failing backs off rather than hammering the provider: the next scheduled run
        // picks the stragglers up anyway, so there is nothing to gain from an unbounded retry chain.
        return if (failures > 0 && !CloudCopyPolicy.isLastAttempt(runAttemptCount)) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "CV:SyncSweep"
        private const val DEFAULT_MIME = "audio/ogg"
    }
}
