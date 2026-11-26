package com.example.data.firebase

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import com.google.firebase.auth.OAuthProvider
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

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

/**
 * Silent automatic sign-in on startup for returning users who already authorized UChat.
 * Also completes any pending Firebase browser OAuth result if the Activity was recreated.
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
            val pending = Firebase.auth.pendingAuthResult
            if (pending != null) {
                pending.await()
                if (Firebase.auth.currentUser != null) {
                    onAuthSuccess()
                    return@launch
                }
            }

            val activityContext = context.findActivity() ?: context
            val webClientId = activityContext.getString(R.string.default_web_client_id)
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
                context = activityContext
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

private fun isPlayServicesConfigOrAuthFailure(message: String): Boolean {
    val lower = message.lowercase()
    return lower.contains("28444") ||
        lower.contains("developer console") ||
        lower.contains("reauth") ||
        lower.contains("16:") ||
        lower.contains("[16]") ||
        lower.contains("caller is not authorized") ||
        lower.contains("cannot find a matching credential") ||
        lower.contains("configuration") ||
        lower.contains("sha") ||
        lower.contains("signature")
}

/**
 * Firebase Web OAuth fallback using official Firebase OAuthProvider("google.com").
 * Works reliably on physical devices even if Google Play Services One-Tap fails due to
 * OEM Credential Manager quirks or a mismatched debug keystore SHA-1.
 */
fun signInWithGoogleBrowser(
    context: Context,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope
) {
    val activity = context.findActivity()
    if (activity == null) {
        onAuthError("Unable to launch Google Sign-In browser from this screen.")
        return
    }
    scope.launch {
        try {
            val pending = Firebase.auth.pendingAuthResult
            if (pending != null) {
                pending.await()
            } else {
                val provider = OAuthProvider.newBuilder("google.com")
                    .addCustomParameter("prompt", "select_account")
                    .build()
                Firebase.auth.startActivityForSignInWithProvider(activity, provider).await()
            }
            if (Firebase.auth.currentUser != null) {
                onAuthSuccess()
            } else {
                onAuthError("Google Sign-In did not complete. Please try again.")
            }
        } catch (e: Exception) {
            Log.e(AUTH_TAG, "Browser Google Sign-In failed: ${e.message}", e)
            val msg = e.message.orEmpty()
            if (msg.contains("canceled", ignoreCase = true) || msg.contains("cancelled", ignoreCase = true)) {
                onAuthError("Sign-in was cancelled. Tap below to try again.")
            } else {
                onAuthError(msg.ifBlank { "Google Sign-In failed. Check your internet connection and try again." })
            }
        }
    }
}

/**
 * Interactive Google Sign-In flow triggered by the Continue with Google button.
 * Uses Jetpack Credential Manager (GetSignInWithGoogleOption) first, and seamlessly falls back
 * to Firebase OAuthProvider("google.com") if Play Services rejects the APK SHA-1 or fails to
 * return a credential on a physical device.
 */
fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: (String?) -> Unit = {}
) {
    val activity = context.findActivity()
    val activityContext: Context = activity ?: context

    scope.launch {
        try {
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
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
                onAuthError("Unexpected credential type received. Please try Sign in via Browser below.")
            }
        } catch (e: GetCredentialCancellationException) {
            val detail = (e.errorMessage?.toString() ?: e.message).orEmpty()
            Log.w(AUTH_TAG, "Credential Manager cancelled: $detail", e)
            if (isPlayServicesConfigOrAuthFailure(detail) && activity != null) {
                signInWithGoogleBrowser(
                    context = activity,
                    onAuthSuccess = onAuthSuccess,
                    onAuthError = onAuthError,
                    scope = scope
                )
            } else {
                onAuthCancelled(
                    "Google quick sign-in closed before finishing. If you selected your Gmail and nothing happened, tap 'Sign in via Browser' below."
                )
            }
        } catch (e: GetCredentialException) {
            val detail = (e.errorMessage?.toString() ?: e.message).orEmpty()
            Log.e(AUTH_TAG, "Google Sign-In failed via Credential Manager: $detail", e)
            if (activity != null) {
                signInWithGoogleBrowser(
                    context = activity,
                    onAuthSuccess = onAuthSuccess,
                    onAuthError = onAuthError,
                    scope = scope
                )
            } else {
                onAuthError(detail.ifBlank { "Google Sign-In failed. Please try again." })
            }
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(AUTH_TAG, "Failed to parse Google ID token", e)
            onAuthError("Unable to verify Google account token. Try 'Sign in via Browser' below.")
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
