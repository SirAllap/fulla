// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

/**
 * Everything that can go wrong talking to the server, as ordinary values.
 *
 * [code] is the backend's machine-readable code (docs/api.md), or one of the
 * transport's own below. [message] is for people and may be shown as is.
 */
class FullaError(val code: String, override val message: String, val status: Int = 0) : Exception(message) {

    /** Worth trying again later without anybody changing anything. */
    val isTransient: Boolean get() = code == NETWORK || code == SERVER || code == RATE_LIMITED

    /** Somebody has to sign in again. */
    val needsSignIn: Boolean get() = code == NOT_AUTHENTICATED || code == SESSION_EXPIRED

    override fun toString(): String = "FullaError($code, $status)"

    companion object {
        const val NETWORK = "network"
        const val SERVER = "server"
        const val RATE_LIMITED = "too_many_attempts"
        const val NOT_AUTHENTICATED = "not_authenticated"
        const val SESSION_EXPIRED = "session_expired"
        const val INVALID_CREDENTIALS = "invalid_credentials"
        const val EMAIL_NOT_CONFIRMED = "email_not_confirmed"
        const val FOREIGN_HOST = "foreign_host"
        const val BAD_RESPONSE = "bad_response"
        const val NOT_MEMBER = "not_member"
        const val FORBIDDEN_ROLE = "forbidden_role"
        const val VALIDATION_FAILED = "validation_failed"
        const val DIGEST_MISMATCH = "update_digest_mismatch"
        const val SIGNATURE_MISMATCH = "update_signature_mismatch"
    }
}
