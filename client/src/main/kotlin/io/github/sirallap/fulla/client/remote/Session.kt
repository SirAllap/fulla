// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/** A signed-in person's tokens. Never logged, never shown. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds. */
    val expiresAt: Long,
    val userId: String,
    val email: String?,
) {
    override fun toString(): String = "Session(user=$userId)"
}

/** Where a session is kept between launches. The app encrypts it at rest. */
interface SessionStore {
    suspend fun load(): Session?
    suspend fun save(session: Session?)
}

class MemorySessionStore(private var session: Session? = null) : SessionStore {
    override suspend fun load(): Session? = session
    override suspend fun save(session: Session?) { this.session = session }
}
