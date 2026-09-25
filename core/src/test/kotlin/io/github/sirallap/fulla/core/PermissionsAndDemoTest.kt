// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.balance.Balances
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Role
import io.github.sirallap.fulla.core.model.TransactionValidator
import io.github.sirallap.fulla.core.roles.Permissions
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsAndDemoTest {
    private val owner = Member("o", "Alice", "A", role = Role.OWNER, hasAccount = true)
    private val admin = Member("a", "Bob", "B", role = Role.ADMIN, hasAccount = true)
    private val member = Member("m", "Carol", "C", role = Role.MEMBER, hasAccount = true)

    @Test
    fun `the role table matches the database`() {
        assertTrue(Permissions.canEditTransactions(member))
        assertFalse(Permissions.canEditStructure(member))
        assertTrue(Permissions.canEditStructure(admin))
        assertFalse(Permissions.canChangeCurrencyOrLimit(admin))
        assertTrue(Permissions.canChangeCurrencyOrLimit(owner))
        assertTrue(Permissions.canInvite(admin, Role.MEMBER))
        assertFalse(Permissions.canInvite(admin, Role.ADMIN))
        assertTrue(Permissions.canInvite(owner, Role.ADMIN))
        assertFalse(Permissions.canInvite(owner, Role.OWNER))
        assertTrue(Permissions.canRemove(admin, member))
        assertFalse(Permissions.canRemove(admin, owner))
        assertFalse(Permissions.canRemove(admin, admin.copy(id = "a2")))
        assertTrue(Permissions.canRemove(owner, admin))
        assertFalse(Permissions.canRemove(owner, owner))
        assertFalse(Permissions.canLeave(owner))
        assertTrue(Permissions.canEditMember(member, member))
        assertFalse(Permissions.canEditMember(member, admin))
    }

    @Test
    fun `demo data is valid, balanced, deterministic and tagged`() {
        val today = LocalDate.of(2030, 3, 20)
        val demo = DemoData.build(today)
        assertEquals(demo, DemoData.build(today))
        assertTrue(demo.transactions.size > 50)
        assertTrue(demo.transactions.all { DemoData.TAG in it.tags })
        for (t in demo.transactions) {
            assertEquals(emptyList(), TransactionValidator.problems(t, demo.config), t.toString())
        }
        assertEquals(0L, Balances.of(demo.transactions, demo.config.members.map { it.id }).sumOf { it.balanceMinor })
        assertEquals(listOf("Alice", "Bob", "Carol"), demo.config.members.map { it.displayName })
    }
}
