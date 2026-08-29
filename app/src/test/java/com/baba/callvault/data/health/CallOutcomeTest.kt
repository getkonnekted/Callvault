package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Test

class CallOutcomeTest {

    @Test
    fun `a recording with bytes and a heard far party is verified`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = true))
    }

    @Test
    fun `a carrier recording with bytes is verified when there is no far-party signal`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = null))
    }

    @Test
    fun `an empty recording is an empty-file failure`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(0L, daemonDied = false, farPartyHeard = true)
        )
    }

    @Test
    fun `a dead daemon outranks the empty file it caused`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.DAEMON_DIED),
            CallOutcomes.of(0L, daemonDied = true, farPartyHeard = null)
        )
    }

    @Test
    fun `bytes but an unheard far party is one-sided`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.ONE_SIDED),
            CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = false)
        )
    }

    @Test
    fun `a negative size is judged empty here, which is why a caller must never pass it`() {
        // SafHelper.fileSize() returns -1 for "unknown size", never for "empty" — that's 0. This
        // function has no way to tell the two apart, so it judges anything <= 0 as EMPTY_FILE. That is
        // exactly why VoipRecordingCoordinator.onCallEnded must special-case a negative size and skip
        // calling this at all, rather than let an unknowable size render as a false empty-file failure.
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(-1L, daemonDied = false, farPartyHeard = null)
        )
    }
}
