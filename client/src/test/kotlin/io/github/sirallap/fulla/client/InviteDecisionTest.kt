// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.InviteDecision
import io.github.sirallap.fulla.client.remote.InviteLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InviteDecisionTest {
    private val hosted = Endpoint("https://abcdefghijklmnopqrst.supabase.co", "hosted-key")
    private val other = Endpoint("https://yourprojectrefxxxxxx.supabase.co", "other-key")
    private val invite = InviteLink(other, "CODE12345")

    @Test
    fun `no saved endpoint and no hosted build proceeds straight to the invite's project`() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = null, hasConnectedHouseholds = false)
        assertEquals(InviteDecision.Outcome.Proceed(other), outcome)
    }

    @Test
    fun `a hosted build with nothing saved still compares against the hosted endpoint, not null`() {
        val sameAsHosted = InviteLink(hosted, "CODE12345")
        val outcome = InviteDecision.evaluate(sameAsHosted, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertEquals(InviteDecision.Outcome.Proceed(hosted), outcome)
    }

    @Test
    fun `an invite for a different project than the hosted one, with a household already connected, asks to confirm`() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertIs<InviteDecision.Outcome.ConfirmSwitch>(outcome)
        assertEquals(other, (outcome as InviteDecision.Outcome.ConfirmSwitch).endpoint)
    }

    @Test
    fun `a different project is fine to proceed to when nothing is connected yet`() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = hosted, hasConnectedHouseholds = false)
        assertEquals(InviteDecision.Outcome.Proceed(other), outcome)
    }

    @Test
    fun `an invite for the project already saved proceeds, even with households connected`() {
        val sameInvite = InviteLink(hosted, "CODE12345")
        val outcome = InviteDecision.evaluate(sameInvite, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertEquals(InviteDecision.Outcome.Proceed(hosted), outcome)
    }
}
