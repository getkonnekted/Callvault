/*

* CallVault: FOSS call recording, self-contained over embedded ADB
* Copyright (C) 2026-present The CallVault Authors
* This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
* The full license text is available in the LICENSE file at the root of this project.
* This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  */

package com.baba.callvault

import android.app.Application
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.services.recording.VoipCaptureController
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.transcription.WhisperManager
import com.baba.callvault.utils.AppLogger

/**

 * CallVaultApplication is run when the app process is created.
 *
 * It is the earliest application-level entry point and is responsible for
 * initializing global application infrastructure.
 */
class CallVaultApplication : Application() {

    private companion object {


        const val TAG = "CV:CallVaultApplication"

        /** Old CallMonitorService notification id (pre-consolidation). */
        const val LEGACY_READINESS_NOTIF_ID = 4714

        /** Old RecorderReadinessNotifier notification id. */
        const val LEGACY_LAUNCH_NOTIF_ID = 4715


    }

    override fun onCreate() {
        super.onCreate()


        AppLogger.init(applicationContext)

        /*
         * -----------------------------------------------------------------
         * Whisper initialization
         * -----------------------------------------------------------------
         *
         * Validate the complete Whisper startup chain:
         *
         * APK assets
         *      ↓
         * ggml-tiny.bin
         *      ↓
         * filesDir/whisper/ggml-tiny.bin
         *      ↓
         * WhisperNative.nativeInit()
         *      ↓
         * JNI
         *      ↓
         * whisper.cpp
         *
         * This intentionally does NOT start the transcription worker yet.
         * The audio pipeline will be connected only after nativeInit() has
         * been validated successfully on the physical device.
         */
        Thread {
            runCatching {

                AppLogger.i(
                    TAG,
                    "Starting Whisper initialization"
                )

                val success =
                    WhisperManager.initialize(
                        applicationContext
                    )

                if (success) {

                    AppLogger.i(
                        TAG,
                        "Whisper startup initialization succeeded"
                    )

                } else {

                    AppLogger.w(
                        TAG,
                        "Whisper startup initialization FAILED"
                    )
                }

            }.onFailure { throwable ->

                AppLogger.w(
                    TAG,
                    "Whisper startup initialization error: " +
                            "${throwable.javaClass.simpleName}: " +
                            throwable.message
                )
            }

        }.apply {

            isDaemon = true
            name = "cv-whisper-init"

        }.start()

        /*
         * -----------------------------------------------------------------
         * Notification migration cleanup
         * -----------------------------------------------------------------
         */

        // Migration: pre-consolidation builds showed readiness from THREE
        // sources, duplicating the permanent keep-alive notification.
        // Cancel stale notification ids once at startup.
        runCatching {

            getSystemService(
                android.app.NotificationManager::class.java
            )?.apply {

                cancel(LEGACY_READINESS_NOTIF_ID)
                cancel(LEGACY_LAUNCH_NOTIF_ID)
            }
        }

        /*
         * -----------------------------------------------------------------
         * Recorder daemon readiness
         * -----------------------------------------------------------------
         */

        // The VoIP capture policy lives in the daemon and dies with it.
        // Re-arm it whenever a fresh daemon binder becomes available.
        RecorderConnection.onDaemonReady = {

            if (
                AppPreferences(
                    applicationContext
                ).isVoipRecordingEnabled()
            ) {

                Thread {

                    runCatching {

                        VoipCaptureController.sync(
                            applicationContext
                        )

                    }.onFailure {

                        AppLogger.w(
                            TAG,
                            "VoIP re-arm failed: ${it.message}"
                        )
                    }

                }.apply {

                    isDaemon = true
                    name = "cv-voip-rearm"

                }.start()
            }
        }

        /*
         * -----------------------------------------------------------------
         * Debug notification
         * -----------------------------------------------------------------
         */

        // Re-assert the debug logging reminder when logging was left enabled
        // across an application restart.
        runCatching {

            DebugNotificationHelper.sync(
                applicationContext
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Debug notification sync failed: ${it.message}"
            )
        }

        /*
         * -----------------------------------------------------------------
         * Retention scheduler
         * -----------------------------------------------------------------
         */

        // Reconcile the daily retention sweep with saved preferences.
        runCatching {

            RetentionScheduler.apply(
                applicationContext
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Retention scheduler apply failed: ${it.message}"
            )
        }

        /*
         * -----------------------------------------------------------------
         * Update scheduler
         * -----------------------------------------------------------------
         */

        // Reconcile the periodic update check and perform an immediate
        // throttled check when appropriate.
        runCatching {

            UpdateScheduler.apply(
                applicationContext
            )

            UpdateScheduler.checkNowIfDue(
                applicationContext
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Update scheduler apply failed: ${it.message}"
            )
        }

        /*
         * -----------------------------------------------------------------
         * Cloud synchronization scheduler
         * -----------------------------------------------------------------
         */

        // Reconcile the periodic cloud synchronization schedule.
        runCatching {

            SyncScheduler.apply(
                applicationContext
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Sync scheduler apply failed: ${it.message}"
            )
        }

        /*
         * -----------------------------------------------------------------
         * Recorder daemon warmup
         * -----------------------------------------------------------------
         */

        // If ADB was already paired, proactively bring up the persistent
        // recorder daemon in the background.
        if (
            AppPreferences(
                applicationContext
            ).isAdbPaired()
        ) {

            Thread {

                runCatching {

                    RecorderServerLauncher.ensureServerRunning(
                        applicationContext
                    )

                }.onFailure {

                    AppLogger.w(
                        TAG,
                        "Startup recorder-daemon warmup failed: ${it.message}"
                    )
                }

            }.apply {

                isDaemon = true
                name = "cv-recorder-warmup"

            }.start()
        }


    }
}
