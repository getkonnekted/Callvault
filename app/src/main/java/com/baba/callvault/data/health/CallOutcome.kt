/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

/** What a finished call proved. */
sealed interface CallOutcome {
    data object Verified : CallOutcome
    data class Failed(val reason: FailureReason) : CallOutcome
}

object CallOutcomes {

    /**
     * Judges a finished call from what the recording path already knows.
     *
     * @param sizeBytes     bytes actually captured (the trusted count, not a cloud provider's length).
     * @param daemonDied    true when the recorder was lost mid-call; it outranks the empty file it caused,
     *                      because "the recorder stopped" is the actionable message.
     * @param farPartyHeard true/false where observable, null where the capture path cannot tell — which
     *                      must not be read as silence. Carrier capture passes null until the follow-on
     *                      plan adds PCM peak detection.
     */
    fun of(sizeBytes: Long, daemonDied: Boolean, farPartyHeard: Boolean?): CallOutcome = when {
        daemonDied -> CallOutcome.Failed(FailureReason.DAEMON_DIED)
        sizeBytes <= 0L -> CallOutcome.Failed(FailureReason.EMPTY_FILE)
        farPartyHeard == false -> CallOutcome.Failed(FailureReason.ONE_SIDED)
        else -> CallOutcome.Verified
    }
}

/** Persists [outcome] and remembers that this call was observed at all. */
fun SetupHealthStore.record(outcome: CallOutcome, atMillis: Long, label: String?, fingerprint: String) {
    observeCall(atMillis)
    when (outcome) {
        is CallOutcome.Verified -> recordVerified(atMillis, fingerprint)
        is CallOutcome.Failed -> recordFailure(atMillis, outcome.reason, label)
    }
}
