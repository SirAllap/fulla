// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.roles

import io.github.sirallap.fulla.core.model.Member
import io.github.sirallap.fulla.core.model.Role

/**
 * What a member may do, so the app can hide what their role does not allow.
 * The database enforces the same table (docs/api.md); this is only so the app
 * never offers a button that would be refused.
 */
object Permissions {

    fun canEditTransactions(me: Member): Boolean = me.isActive
    /** A trip is closer to spending than to configuration: any active member may add one. */
    fun canEditTrips(me: Member): Boolean = me.isActive
    fun canImport(me: Member): Boolean = me.isActive
    fun canEditStructure(me: Member): Boolean = me.isActive && me.role.atLeast(Role.ADMIN)
    fun canEditHouseholdSettings(me: Member): Boolean = canEditStructure(me)
    fun canChangeCurrencyOrLimit(me: Member): Boolean = me.isActive && me.role == Role.OWNER
    fun canInvite(me: Member, asRole: Role): Boolean = when (asRole) {
        Role.MEMBER -> me.isActive && me.role.atLeast(Role.ADMIN)
        Role.ADMIN -> me.isActive && me.role == Role.OWNER
        Role.OWNER -> false
    }
    fun canAddMemberWithoutAccount(me: Member): Boolean = canEditStructure(me)
    fun canChangeRoles(me: Member): Boolean = me.isActive && me.role == Role.OWNER
    fun canTransferOwnership(me: Member): Boolean = me.isActive && me.role == Role.OWNER
    fun canLeave(me: Member): Boolean = me.isActive && me.role != Role.OWNER

    fun canEditMember(me: Member, target: Member): Boolean =
        me.isActive && (me.id == target.id || me.role.atLeast(Role.ADMIN))

    fun canRemove(me: Member, target: Member): Boolean =
        me.isActive && target.isActive && me.id != target.id && when (target.role) {
            Role.OWNER -> false
            Role.ADMIN -> me.role == Role.OWNER
            Role.MEMBER -> me.role.atLeast(Role.ADMIN)
        }
}
