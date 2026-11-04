// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.wire.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the database's change history says is read as it is: who, what, which fields, the row before and after. */
class AuditWireTest {
    private fun page(text: String) = Wire.auditPage(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `an edit is read with its fields and the row before and after`() {
        val p = page("""{"events":[{"id":7,"at":"2030-01-15T12:00:00Z","entity":"transaction","entity_id":"t1","action":"update",
            "actor_member_id":"m1","source":"live","changed":["amount_minor"],
            "before":{"amount_minor":1250},"after":{"amount_minor":1450}}],"has_more":true}""")
        assertTrue(p.hasMore)
        val e = p.events.single()
        assertEquals(7L, e.id)
        assertEquals("update", e.action)
        assertEquals("m1", e.actorMemberId)
        assertEquals(listOf("amount_minor"), e.changed)
        assertEquals("1250", e.before!!["amount_minor"].toString())
        assertEquals("1450", e.after!!["amount_minor"].toString())
        assertEquals(false, e.backfill)
    }

    @Test
    fun `a line from before the history existed has no before and after, and says so`() {
        val e = page("""{"events":[{"id":1,"at":"2030-01-01T00:00:00Z","entity":"transaction","entity_id":"t1","action":"delete",
            "actor_member_id":null,"source":"backfill","changed":[],"before":null,"after":null}],"has_more":false}""").events.single()
        assertTrue(e.backfill)
        assertNull(e.actorMemberId)
        assertNull(e.before)
        assertNull(e.after)
    }

    @Test
    fun `a page with nothing in it is empty`() {
        assertTrue(page("""{"events":[],"has_more":false}""").events.isEmpty())
    }
}
