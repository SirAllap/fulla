// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.local

import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.model.Transaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A whole household in one file: its structure and every row, deleted ones
 * included, exactly as the phone holds them.
 *
 * A household that lives on one phone has no other copy anywhere (backups to
 * the phone maker's cloud are off on purpose), so this file is its only
 * insurance against a lost or reset phone. It restores as a phone-only
 * household with every id kept, and can be shared again afterwards.
 */
object Backup {
    const val FORMAT = "fulla-backup"
    const val VERSION = 1

    class Unreadable(val reason: Reason) : Exception(reason.name)

    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, DAMAGED }

    data class Contents(val bundle: JsonObject, val transactions: List<Transaction>) {
        val householdId: String get() = Wire.config(bundle).household.id
        val householdName: String get() = Wire.config(bundle).household.name
    }

    fun write(bundle: JsonObject, rows: List<Transaction>, createdAt: String): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("created_at", createdAt)
        put("config", bundle)
        put("transactions", JsonArray(rows.map { t -> JsonObject(Wire.transaction(t) + ("server_seq" to JsonPrimitive(t.serverSeq))) }))
    }.toString()

    fun read(text: String): Contents {
        val o = runCatching { Wire.json.parseToJsonElement(text.trim().removePrefix("\uFEFF")).jsonObject }.getOrNull()
            ?: throw Unreadable(Reason.NOT_A_BACKUP)
        if ((o["format"] as? JsonPrimitive)?.content != FORMAT) throw Unreadable(Reason.NOT_A_BACKUP)
        val version = (o["version"] as? JsonPrimitive)?.intOrNull ?: throw Unreadable(Reason.DAMAGED)
        if (version > VERSION) throw Unreadable(Reason.NEWER_VERSION)
        return runCatching {
            val bundle = o["config"]!!.jsonObject
            Wire.config(bundle) // must parse
            Contents(bundle, Wire.list(o["transactions"]).map(Wire::transaction))
        }.getOrElse { throw Unreadable(Reason.DAMAGED) }
    }
}
