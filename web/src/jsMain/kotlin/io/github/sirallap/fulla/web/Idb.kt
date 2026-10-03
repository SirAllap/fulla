// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The browser's own database, for what has to survive closing the page: the
 * household, as a backup file's text. A browser may refuse it (some private
 * windows); then what is kept lives only until the page closes, and
 * [available] says so.
 */
object Idb {
    private const val NAME = "fulla"
    private const val STORE = "kv"

    private var db: dynamic = null
    private val memory = LinkedHashMap<String, String>()

    /** Whether what is written here outlives the page. */
    var available: Boolean = false
        private set

    private fun request(r: dynamic): Promise<dynamic> = Promise { resolve, reject ->
        r.onsuccess = { resolve(r.result) }
        r.onerror = { reject(Throwable("The browser's storage refused the request.")) }
    }

    suspend fun open() {
        if (db != null) return
        try {
            val factory = js("globalThis.indexedDB")
            if (factory == null || factory == undefined) return
            val r = factory.open(NAME, 1)
            r.onupgradeneeded = { r.result.createObjectStore(STORE) }
            db = request(r).await()
            available = true
        } catch (e: Throwable) {
            db = null
            available = false
        }
    }

    private fun store(mode: String): dynamic = db.transaction(STORE, mode).objectStore(STORE)

    suspend fun get(key: String): String? {
        if (db == null) return memory[key]
        return request(store("readonly").get(key)).await() as? String
    }

    suspend fun put(key: String, value: String) {
        if (db == null) { memory[key] = value; return }
        request(store("readwrite").put(value, key)).await()
    }

    suspend fun remove(key: String) {
        if (db == null) { memory.remove(key); return }
        request(store("readwrite").delete(key)).await()
    }

    suspend fun keys(): List<String> {
        if (db == null) return memory.keys.toList()
        val result = request(store("readonly").getAllKeys()).await()
        return (js("Array.from(result)") as Array<String>).toList()
    }

    /** Asks the browser not to clear this storage when it is short of room. */
    suspend fun persist(): Boolean = try {
        val storage = js("navigator.storage")
        if (storage == null || storage == undefined || storage.persist == undefined) false
        else (storage.persist() as Promise<Boolean>).await()
    } catch (e: Throwable) {
        false
    }
}
