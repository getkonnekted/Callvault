/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.RecordingPolicy.CarrierAction
import com.baba.callvault.services.recording.RecordingPolicy.VoipAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matrix is small enough to test exhaustively, and worth it: this decides whether a call is
 * recorded at all, and getting it wrong fails silently — the user finds out from the recording that
 * is not there.
 */
class RecordingPolicyTest {

    // ---- Carrier ----

    @Test
    fun `records a carrier call when the rules say so`() {
        assertEquals(
            CarrierAction.RECORD,
            RecordingPolicy.forCarrierCall(carrierEnabled = true, autoRecord = true)
        )
    }

    @Test
    fun `offers to record when auto-record is off - this is Ask me, not Off`() {
        // The long-standing behaviour, and the reason a separate Off state was needed: turning
        // auto-record off does NOT stop CallVault from prompting on every call.
        assertEquals(
            CarrierAction.OFFER,
            RecordingPolicy.forCarrierCall(carrierEnabled = true, autoRecord = false)
        )
    }

    @Test
    fun `ignores a carrier call entirely when phone recording is off`() {
        assertEquals(
            CarrierAction.IGNORE,
            RecordingPolicy.forCarrierCall(carrierEnabled = false, autoRecord = false)
        )
    }

    @Test
    fun `the master switch beats the per-direction rules`() {
        // THE CASE THAT MATTERS: someone recording app calls only leaves auto-record on and expects
        // silence on phone calls anyway. If the master switch did not win here, they would keep
        // getting recordings they switched off.
        assertEquals(
            CarrierAction.IGNORE,
            RecordingPolicy.forCarrierCall(carrierEnabled = false, autoRecord = true)
        )
    }

    // ---- App calls ----

    @Test
    fun `records an app call automatically by default`() {
        assertEquals(
            VoipAction.RECORD,
            RecordingPolicy.forVoipCall(voipEnabled = true, autoStart = true)
        )
    }

    @Test
    fun `offers to record an app call when auto-start is off`() {
        assertEquals(
            VoipAction.OFFER,
            RecordingPolicy.forVoipCall(voipEnabled = true, autoStart = false)
        )
    }

    @Test
    fun `ignores app calls when the feature is off, whatever auto-start says`() {
        // Auto-start is meaningless without detection: with VoIP recording off nothing is watching,
        // so there is nothing to ask about.
        assertEquals(
            VoipAction.IGNORE,
            RecordingPolicy.forVoipCall(voipEnabled = false, autoStart = true)
        )
        assertEquals(
            VoipAction.IGNORE,
            RecordingPolicy.forVoipCall(voipEnabled = false, autoStart = false)
        )
    }

    // ---- Status card ----

    @Test
    fun `a call the user chose not to record is not a gap`() {
        // Otherwise "app calls only" would fill the status card with alarms about every phone call.
        assertFalse(
            RecordingPolicy.expectsCarrierRecording(
                carrierEnabled = false,
                autoRecordForDirection = true,
            )
        )
    }

    @Test
    fun `a call that should have been recorded still counts as a gap`() {
        assertTrue(
            RecordingPolicy.expectsCarrierRecording(
                carrierEnabled = true,
                autoRecordForDirection = true,
            )
        )
    }

    @Test
    fun `Ask me calls are not gaps either`() {
        // Pre-existing behaviour, asserted so the new master switch cannot quietly change it: with
        // auto-record off the user was only ever offered the call, never promised it.
        assertFalse(
            RecordingPolicy.expectsCarrierRecording(
                carrierEnabled = true,
                autoRecordForDirection = false,
            )
        )
    }
}
