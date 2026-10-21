package eu.kanade.tachiyomi.data.track.comick.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComickOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String? = null,
    /** Seconds until expiry, per the token response (`expires_in: 900`). */
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    /**
     * Absolute expiry in epoch seconds. Upstream's adapter expects `expires_at`;
     * the documented example response shows `expires_in`. Support both, preferring
     * the absolute value when present.
     */
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val scope: String = "",
) {
    private fun expiryMillis(): Long {
        expiresAt?.takeIf { it > 0 }?.let { return it * 1000 }
        val now = System.currentTimeMillis()
        return now + (expiresIn?.takeIf { it > 0 } ?: DEFAULT_EXPIRY_SECONDS) * 1000
    }

    fun isExpired(): Boolean = System.currentTimeMillis() + REFRESH_MARGIN_MS >= expiryMillis()

    companion object {
        private const val DEFAULT_EXPIRY_SECONDS = 900L
        private const val REFRESH_MARGIN_MS = 60_000L
    }
}
