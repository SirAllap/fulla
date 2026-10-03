// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/**
 * What an incoming invite means for the endpoint this phone already talks to.
 *
 * The comparison has to be against the *effective* endpoint — the saved one,
 * or the build's own hosted project when nothing is saved yet — never the
 * saved endpoint alone. A hosted build has no saved endpoint on a fresh
 * install, so comparing against `null` would let any invite through
 * unconfirmed, including one pointing at an attacker's project.
 *
 * The outcome never persists anything by itself: [Outcome.Proceed] and
 * [Outcome.ConfirmSwitch] both wait for the person to confirm on the join
 * screen, which is what is allowed to call `settings.setEndpoint`.
 */
object InviteDecision {

    sealed class Outcome {
        /** No household is connected elsewhere, or the invite is for the same project already in use: go straight to the join step, after showing the host. */
        data class Proceed(val endpoint: Endpoint) : Outcome()

        /** A different project than the one already connected: the person must confirm before this phone's session and endpoint switch to it. */
        data class ConfirmSwitch(val endpoint: Endpoint) : Outcome()
    }

    /**
     * [effectiveEndpoint] is the project this phone would sync with right
     * now: the saved endpoint, or the build's hosted one. [hasConnectedHouseholds]
     * is whether this phone already has a household synced through it — an
     * unconnected phone has nothing to switch away from.
     */
    fun evaluate(invite: InviteLink, effectiveEndpoint: Endpoint?, hasConnectedHouseholds: Boolean): Outcome =
        if (effectiveEndpoint != null && effectiveEndpoint != invite.endpoint && hasConnectedHouseholds) {
            Outcome.ConfirmSwitch(invite.endpoint)
        } else {
            Outcome.Proceed(invite.endpoint)
        }
}
