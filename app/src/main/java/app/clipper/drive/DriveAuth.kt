package app.clipper.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    @Volatile private var token: String? = null
    @Volatile private var tokenTime: Long = 0
    fun accept(accessToken: String) { token = accessToken; tokenTime = android.os.SystemClock.elapsedRealtime() }
    fun clear() { token = null; tokenTime = 0 }
    fun connected(): Boolean = token != null
    suspend fun accessToken(context: Context): String {
        val cached = token
        if (cached != null && android.os.SystemClock.elapsedRealtime() - tokenTime < 45 * 60_000) return cached
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        check(!result.hasResolution()) { "Google Drive needs permission. Reconnect in Settings, then resume this export." }
        val refreshed = result.accessToken ?: error("Reconnect Google Drive in Settings.")
        accept(refreshed)
        return refreshed
    }
}
