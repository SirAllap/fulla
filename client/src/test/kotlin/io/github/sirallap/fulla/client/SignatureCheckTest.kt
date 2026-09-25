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
    fun `the same single signer on both sides matches`() {
        assertTrue(SignatureCheck.matches(setOf(a), setOf(a)))
    }

    @Test
    fun `a different signer never matches`() {
        assertFalse(SignatureCheck.matches(setOf(a), setOf(b)))
    }

    @Test
    fun `an empty set on either side refuses rather than treating it as agreement`() {
        assertFalse(SignatureCheck.matches(emptySet(), emptySet()))
        assertFalse(SignatureCheck.matches(setOf(a), emptySet()))
        assertFalse(SignatureCheck.matches(emptySet(), setOf(a)))
    }

    @Test
    fun `multiple signers must match as a set, not just overlap`() {
        assertTrue(SignatureCheck.matches(setOf(a, b), setOf(b, a)))
        assertFalse(SignatureCheck.matches(setOf(a, b), setOf(a)))
    }
}
