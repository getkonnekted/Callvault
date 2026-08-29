/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.telephony.TelephonyManager
import com.baba.callvault.utils.AppLogger

/**
 * Keeps the app-call (VoIP) path off a carrier call.
 *
 * [VoipCallDetector] recognises an app call by one signal: the audio mode being
 * `MODE_IN_COMMUNICATION`. That worked because carrier calls conventionally use `MODE_IN_CALL`
 * instead — measured on a OnePlus 12 on 2026-08-05, every carrier call was `MODE_IN_CALL` set by
 * `com.android.server.telecom`, while only WhatsApp used `MODE_IN_COMMUNICATION`.
 *
 * But that is a ROM convention, not a rule, and until this class existed the only thing enforcing it
 * was a comment saying the two paths "cannot collide". Wi-Fi calling and some VoLTE stacks carry the
 * call over IMS, and those can present as `MODE_IN_COMMUNICATION`. On such a device the app-call path
 * would treat the user's phone call as an app call: a second recording of a call already being
 * recorded properly, and a plain `MIC` capture contending with the dialer for the microphone — made
 * worse by the microphone-reclaim logic added in 1.5.5, which would keep taking it back mid-call.
 *
 * The telephony call state is the authority, so it is consulted rather than trusted to disagree.
 */
object VoipTelephonyGate {

    private const val TAG = "CV:VoipGate"

    /**
     * Whether an app-call capture may START.
     *
     * Ringing blocks a start as firmly as an answered call does: the audio mode moves around while a
     * call is being set up, and a start that slips through in that window produces exactly the
     * recording this guard exists to prevent.
     *
     * Fails **open** on an unrecognised value, matching
     * [com.baba.callvault.system.updates.CallInProgressGate]. A device reporting something unexpected
     * should lose the guard, not lose app-call recording altogether.
     */
    fun mayStart(telephonyState: Int): Boolean = when (telephonyState) {
        TelephonyManager.CALL_STATE_RINGING,
        TelephonyManager.CALL_STATE_OFFHOOK -> false
        else -> true
    }

    /**
     * Whether an app-call capture already running must STOP.
     *
     * Only an *answered* carrier call qualifies. Ringing does not: an incoming call the user declines
     * must not cost them the app call they were recording, and if they do answer it, the OFFHOOK that
     * follows stops the capture a moment later anyway.
     */
    fun mustStop(telephonyState: Int): Boolean =
        telephonyState == TelephonyManager.CALL_STATE_OFFHOOK

    /** Reads the live telephony state. Any failure reads as IDLE, for the fail-open reason above. */
    fun callState(context: Context): Int = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.callState
            ?: TelephonyManager.CALL_STATE_IDLE
    }.onFailure {
        AppLogger.d(TAG, "Telephony state unreadable (${it.message}) — treating as idle")
    }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

    /** Convenience for the detector: may an app-call capture start on this device right now? */
    fun mayStartNow(context: Context): Boolean = mayStart(callState(context))
}
