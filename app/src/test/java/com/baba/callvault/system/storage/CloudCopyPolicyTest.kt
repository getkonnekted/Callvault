/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the decisions that keep a cloud copy from being uploaded twice — the field bug where Google
 * Drive announced "saved a call" an hour after the call because every retry re-uploaded the recording.
 */
class CloudCopyPolicyTest {

    // ---- verdict on a destination file that already carries the final name

    @Test
    fun `a destination of the same size is already complete`() {
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(existingSize = 9_032_989, sourceSize = 9_032_989))
    }

    @Test
    fun `a truncated destination is partial and must be replaced`() {
        assertEquals(ExistingCopyVerdict.PARTIAL, CloudCopyPolicy.verdict(existingSize = 6_727_062, sourceSize = 9_032_989))
    }

    @Test
    fun `a destination larger than the source is partial too`() {
        assertEquals(ExistingCopyVerdict.PARTIAL, CloudCopyPolicy.verdict(existingSize = 10_000, sourceSize = 9_000))
    }

    @Test
    fun `an unmeasurable destination counts as complete so a settling cloud file is never deleted`() {
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(existingSize = 0, sourceSize = 9_000))
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(existingSize = -1, sourceSize = 9_000))
    }

    @Test
    fun `an unmeasurable source cannot be judged and counts as complete`() {
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(existingSize = 500, sourceSize = 0))
    }

    // ---- retry budget

    @Test
    fun `the first attempts are retried`() {
        assertFalse(CloudCopyPolicy.isLastAttempt(runAttemptCount = 0))
        assertFalse(CloudCopyPolicy.isLastAttempt(runAttemptCount = 1))
    }

    @Test
    fun `the budget runs out instead of retrying forever`() {
        assertTrue(CloudCopyPolicy.isLastAttempt(runAttemptCount = CloudCopyPolicy.MAX_ATTEMPTS - 1))
        assertTrue(CloudCopyPolicy.isLastAttempt(runAttemptCount = CloudCopyPolicy.MAX_ATTEMPTS + 50))
    }

    // ---- staging name

    @Test
    fun `the staging name keeps the extension so the provider cannot rewrite it`() {
        val staged = CloudCopyPolicy.stagingNameFor("20260728_085310.464+0300_voip-WhatsApp_AthenX.ogg")
        assertEquals("20260728_085310.464+0300_voip-WhatsApp_AthenX.cvpart.ogg", staged)
    }

    @Test
    fun `a name without an extension still gets a staging marker`() {
        assertEquals("recording.cvpart", CloudCopyPolicy.stagingNameFor("recording"))
    }

    @Test
    fun `a staging name is recognisable so stale leftovers can be swept`() {
        assertTrue(CloudCopyPolicy.isStagingName(CloudCopyPolicy.stagingNameFor("call.ogg")))
        assertFalse(CloudCopyPolicy.isStagingName("call.ogg"))
    }
}
