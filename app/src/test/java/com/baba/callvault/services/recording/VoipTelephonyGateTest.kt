/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.telephony.TelephonyManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoIP detection keys off `MODE_IN_COMMUNICATION`, which the app-call path assumed no carrier call
 * would ever set. That assumption holds on the devices tested (measured 2026-08-05 on a OnePlus 12:
 * every carrier call went to `MODE_IN_CALL`, set by `com.android.server.telecom`) but it is a ROM
 * convention, not a guarantee — Wi-Fi calling and some VoLTE stacks run the call through IMS and can
 * land in `MODE_IN_COMMUNICATION`. There, the app-call path would record the user's phone call a
 * second time and fight the dialer for the microphone.
 */
class VoipTelephonyGateTest {

    @Test
    fun `allows a capture to start when no carrier call is in progress`() {
        assertTrue(VoipTelephonyGate.mayStart(TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun `blocks a start while the phone is ringing`() {
        // The audio mode wobbles while a call is being set up, so a start must not slip through
        // between the ring and the answer.
        assertFalse(VoipTelephonyGate.mayStart(TelephonyManager.CALL_STATE_RINGING))
    }

    @Test
    fun `blocks a start during an active carrier call`() {
        assertFalse(VoipTelephonyGate.mayStart(TelephonyManager.CALL_STATE_OFFHOOK))
    }

    @Test
    fun `stops a running capture once a carrier call is answered`() {
        assertTrue(VoipTelephonyGate.mustStop(TelephonyManager.CALL_STATE_OFFHOOK))
    }

    @Test
    fun `keeps a running capture while the phone merely rings`() {
        // An incoming call the user declines must not cost them the app call they were recording.
        assertFalse(VoipTelephonyGate.mustStop(TelephonyManager.CALL_STATE_RINGING))
    }

    @Test
    fun `keeps a running capture when no carrier call is in progress`() {
        assertFalse(VoipTelephonyGate.mustStop(TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun `treats an unrecognised telephony state as no carrier call`() {
        // Fails open, matching CallInProgressGate: a device reporting something unexpected should
        // lose the guard, not lose app-call recording altogether.
        val unknown = -1
        assertTrue(VoipTelephonyGate.mayStart(unknown))
        assertFalse(VoipTelephonyGate.mustStop(unknown))
    }
}
