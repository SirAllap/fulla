// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.signin

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.sirallap.fulla.client.remote.Nonce
import io.github.sirallap.fulla.client.remote.Supabase

/**
 * "Continue with Google": Android's own account picker (Credential Manager),
 * then the ID token it returns is exchanged for a session on the household
 * server. Fulla never sees a Google password and asks Google for nothing but
 * who you are.
 */
object GoogleSignIn {

    sealed interface Result {
        /** Signed in; [name] is the Google profile's name, for the household form. */
        data class Done(val name: String?) : Result
        data object Cancelled : Result
        data class Failed(val message: String?) : Result
    }

    suspend fun signIn(context: Context, clientId: String, supabase: Supabase): Result {
        val nonce = Nonce.create()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce.hashed).build())
            .build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.Failed(null)
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            supabase.signInWithIdToken("google", google.idToken, nonce.raw)
            Result.Done(google.displayName)
        } catch (e: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (e: GetCredentialException) {
            Result.Failed(e.message)
        } catch (e: io.github.sirallap.fulla.client.remote.FullaError) {
            Result.Failed(e.message)
        }
    }
}
