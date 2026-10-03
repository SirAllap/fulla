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
    fun no_saved_endpoint_and_no_hosted_build_proceeds_straight_to_the_invite_s_project() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = null, hasConnectedHouseholds = false)
        assertEquals(InviteDecision.Outcome.Proceed(other), outcome)
    }

    @Test
    fun a_hosted_build_with_nothing_saved_still_compares_against_the_hosted_endpoint_not_null() {
        val sameAsHosted = InviteLink(hosted, "CODE12345")
        val outcome = InviteDecision.evaluate(sameAsHosted, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertEquals(InviteDecision.Outcome.Proceed(hosted), outcome)
    }

    @Test
    fun an_invite_for_a_different_project_than_the_hosted_one_with_a_household_already_connected_asks_to_confirm() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertIs<InviteDecision.Outcome.ConfirmSwitch>(outcome)
        assertEquals(other, (outcome as InviteDecision.Outcome.ConfirmSwitch).endpoint)
    }

    @Test
    fun a_different_project_is_fine_to_proceed_to_when_nothing_is_connected_yet() {
        val outcome = InviteDecision.evaluate(invite, effectiveEndpoint = hosted, hasConnectedHouseholds = false)
        assertEquals(InviteDecision.Outcome.Proceed(other), outcome)
    }

    @Test
    fun an_invite_for_the_project_already_saved_proceeds_even_with_households_connected() {
        val sameInvite = InviteLink(hosted, "CODE12345")
        val outcome = InviteDecision.evaluate(sameInvite, effectiveEndpoint = hosted, hasConnectedHouseholds = true)
        assertEquals(InviteDecision.Outcome.Proceed(hosted), outcome)
    }
}
