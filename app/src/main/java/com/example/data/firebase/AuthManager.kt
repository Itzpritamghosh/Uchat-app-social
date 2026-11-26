package com.example.data.firebase

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val AUTH_TAG = "Auth"

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

/**
 * Silent automatic sign-in on startup for returning users who already authorized UChat.
 */
fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    scope.launch {
        try {
            val webClientId = context.getString(R.string.default_web_client_id)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                Firebase.auth.signInWithCredential(firebaseCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (_: NoCredentialException) {
            onUnauthenticated()
        } catch (_: GetCredentialCancellationException) {
            onUnauthenticated()
        } catch (e: Exception) {
            Log.w(AUTH_TAG, "Silent sign-in skipped: ${e.message}")
            onUnauthenticated()
        }
    }
}

/**
 * Interactive Google Sign-In flow triggered by the Sign In with Google button.
 */
fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    scope.launch {
        try {
            val webClientId = context.getString(R.string.default_web_client_id)
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                Firebase.auth.signInWithCredential(firebaseCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type received. Please try again.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(AUTH_TAG, "User cancelled sign-in", e)
            onAuthCancelled()
        } catch (e: GetCredentialException) {
            Log.e(AUTH_TAG, "Google Sign-In failed: ${e.message}", e)
            onAuthError(e.errorMessage?.toString() ?: e.message ?: "Google Sign-In failed. Please try again.")
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(AUTH_TAG, "Failed to parse Google ID token", e)
            onAuthError("Unable to verify Google account token. Please try again.")
        } catch (e: Exception) {
            Log.e(AUTH_TAG, "Authentication failed: ${e.message}", e)
            onAuthError(e.message ?: "Sign-In failed. Check your internet connection and try again.")
        }
    }
}

/**
 * Signs the user out cleanly from Firebase Auth and clears Credential Manager state
 * inside NonCancellable so composition disposal does not interrupt cleanup.
 */
fun signOutUser(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    beforeSignOut: suspend () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            runCatching { beforeSignOut() }
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(AUTH_TAG, "clearCredentialState skipped: ${e.message}")
            }
            Firebase.auth.signOut()
        }
    }
}
