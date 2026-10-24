package com.piggie.tv.data.api

import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.PlaybackOriginPolicy
import okhttp3.Interceptor
import okhttp3.Response

/** Adds validated Jellyfin artwork credentials and reports only genuine authenticated HTTP 401s. */
internal class JellyfinImageAuthenticationInterceptor(
    private val sessionProvider: () -> NativeSession?,
    private val authorizationProvider: (String) -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val authenticatedSession = sessionProvider()?.takeIf { session ->
            PlaybackOriginPolicy.shouldAttachCredentials(
                session.serverUrl,
                original.url.toString(),
            )
        }
        val authenticationTicket = authenticatedSession?.let { session ->
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        }
        val request = original.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("X-Emby-Authorization")
            .removeHeader("X-MediaBrowser-Token")
            .apply {
                authenticatedSession?.let { session ->
                    val authorization = authorizationProvider(session.token)
                    header("Authorization", authorization)
                    header("X-Emby-Authorization", authorization)
                    header("X-MediaBrowser-Token", session.token)
                }
            }
            .build()

        return chain.proceed(request).also { response ->
            if (response.code == 401) {
                AuthSessionCoordinator.notifyAuthenticationRejected(authenticationTicket)
            }
        }
    }
}
