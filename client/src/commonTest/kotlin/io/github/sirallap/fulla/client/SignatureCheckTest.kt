// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.SignatureCheck
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureCheckTest {
    private val a = "aa11223344556677889900112233445566778899001122334455667788990011"
    private val b = "bb11223344556677889900112233445566778899001122334455667788990022"

    @Test
    fun the_same_single_signer_on_both_sides_matches() {
        assertTrue(SignatureCheck.matches(setOf(a), setOf(a)))
    }

    @Test
    fun a_different_signer_never_matches() {
        assertFalse(SignatureCheck.matches(setOf(a), setOf(b)))
    }

    @Test
    fun an_empty_set_on_either_side_refuses_rather_than_treating_it_as_agreement() {
        assertFalse(SignatureCheck.matches(emptySet(), emptySet()))
        assertFalse(SignatureCheck.matches(setOf(a), emptySet()))
        assertFalse(SignatureCheck.matches(emptySet(), setOf(a)))
    }

    @Test
    fun multiple_signers_must_match_as_a_set_not_just_overlap() {
        assertTrue(SignatureCheck.matches(setOf(a, b), setOf(b, a)))
        assertFalse(SignatureCheck.matches(setOf(a, b), setOf(a)))
    }
}
