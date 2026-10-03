package io.github.zyautra.pushbeam.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** Google 로그인과 Firebase ID Token (docs/01 6.2). */
class SessionRepository(private val appContext: Context) {
    private val auth = FirebaseAuth.getInstance()

    val email: String? get() = auth.currentUser?.email

    val isSignedIn: Boolean get() = auth.currentUser != null

    /** 취소하면 false. 그 밖의 실패는 예외. */
    suspend fun signIn(activityContext: Context): Boolean {
        val clientId = webClientId(appContext)
            ?: error("Google 로그인이 설정되지 않았어요 (default_web_client_id 없음)")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val result = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: GetCredentialCancellationException) {
            return false
        }
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        return true
    }

    suspend fun idToken(forceRefresh: Boolean = false): String? =
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token

    suspend fun signOut() {
        auth.signOut()
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun webClientId(context: Context): String? {
        // google-services 플러그인이 만드는 값. Firebase에서 Google 로그인을 켜야 생긴다.
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }
}
