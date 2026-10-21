// AM (DISCORD) -->

// Taken from Animiru. Thank you Quickdev for permission!
// Original library from https://github.com/dead8309/KizzyRPC (Thank you)
// Thank you to the 最高 man for the refactored and simplified code
// https://github.com/saikou-app/saikou
package eu.kanade.tachiyomi.data.connections.discord

import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Constant for logging tag
const val RICH_PRESENCE_TAG = "discord_rpc"

// Constant for application id
internal const val RICH_PRESENCE_APPLICATION_ID = "1558950409090113596"

val DOWNLOAD_BUTTON_LABEL_RES = R.string.discord_download_button
// Houri's own releases. This was Komikku's download page, so a presence button reading
// "Read on Houri" sent users to Komikku's APK.
const val DOWNLOAD_BUTTON_URL = "https://github.com/PineappleTwilight/komikku-pineapple/releases/latest"
const val DISCORD_BUTTON_LABEL = "Discord"
const val DISCORD_BUTTON_URL = "https://discord.gg/JcQZYvCbYH"

@Serializable
data class Activity(
    @SerialName("application_id")
    val applicationId: String? = RICH_PRESENCE_APPLICATION_ID,
    val name: String? = null,
    val details: String? = null,
    val state: String? = null,
    val type: Int? = null,
    val timestamps: Timestamps? = null,
    val assets: Assets? = null,
    val buttons: List<String>? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class Assets(
        @SerialName("large_image")
        val largeImage: String? = null,
        @SerialName("large_text")
        val largeText: String? = null,
        @SerialName("small_image")
        val smallImage: String? = null,
        @SerialName("small_text")
        val smallText: String? = null,
    )

    @Serializable
    data class Metadata(
        @SerialName("button_urls")
        val buttonUrls: List<String>,
    )

    @Serializable
    data class Timestamps(
        val start: Long? = null,
        val end: Long? = null,
        val stop: Long? = null,
    )
}

@Serializable
data class Presence(
    val status: String? = null,
    val afk: Boolean = true,
    val activities: List<Activity> = listOf(),
    val since: Long? = null,
) {
    @Serializable
    data class Response(
        val op: Long,
        val d: Presence,
    )
}

@Serializable
data class Identity(
    val token: String,
    val properties: Properties,
    val compress: Boolean,
    val intents: Long,
) {

    @Serializable
    data class Response(
        val op: Long,
        val d: Identity,
    )

    @Serializable
    data class Properties(
        @SerialName("\$os")
        val os: String,

        @SerialName("\$browser")
        val browser: String,

        @SerialName("\$device")
        val device: String,
    )
}

@Serializable
data class Res(
    val t: String?,
    val s: Int?,
    val op: Int,
    val d: JsonElement,
)

enum class ActivityType(val value: Int) {
    /** Playing a game. */
    PLAYING(0),

    /** Streaming a game. */
    STREAMING(1),

    /** Listening to music. */
    LISTENING(2),

    /** Watching a video. */
    WATCHING(3),

    /** Competing in a game. */
    COMPETING(5),

    /** Custom activity type, not defined by Discord. */
    CUSTOM(4),
}

@Suppress("MagicNumber")
enum class OpCode(val value: Int) {
    /** An event was dispatched. */
    DISPATCH(0),

    /** Fired periodically by the client to keep the connection alive. */
    HEARTBEAT(1),

    /** Starts a new session during the initial handshake. */
    IDENTIFY(2),

    /** Update the client's presence. */
    PRESENCE_UPDATE(3),

    /** Joins/leaves or moves between voice channels. */
    VOICE_STATE(4),

    /** Resume a previous session that was disconnected. */
    RESUME(6),

    /** You should attempt to reconnect and resume immediately. */
    RECONNECT(7),

    /** Request information about offline guild members in a large guild. */
    REQUEST_GUILD_MEMBERS(8),

    /** The session has been invalidated. You should reconnect and identify/resume accordingly */
    INVALID_SESSION(9),

    /** Sent immediately after connecting, contains the heartbeat_interval to use. */
    HELLO(10),

    /** Sent in response to receiving a heartbeat to acknowledge that it has been received. */
    HEARTBEAT_ACK(11),

    /** For future use or unknown opcodes. */
    UNKNOWN(-1),
}

data class ReaderData(
    val incognitoMode: Boolean = false,
    val mangaId: Long? = null,
    val mangaTitle: String? = null,
    val chapterNumber: String? = null,
    val thumbnailUrl: String? = null,
    val startTimestamp: Long? = null,
    // KMK -->
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val sourceName: String? = null,
    // KMK <--
)

// Rich Presence art.
//
// These were Komikku's server emoji, served as mp:emojis/<snowflake>.webp. A snowflake is
// scoped to the server that uploaded it, so those ids belong to Komikku's Discord and render
// Komikku's branding in a Houri presence - and are useless to us, because we cannot mint new
// ones without an upload to our own server.
//
// Discord's IPC accepts an externally-reachable URL for large_image/small_image, and the
// repository carries Houri's own icon, so that is what goes here instead. Raw GitHub serves it
// at a stable path and needs no token.
//
// 192x192 rather than the 1024 Discord prefers: it is the icon the repo already publishes, and
// the presence slot renders far smaller than that anyway.
//
// Every screen shares it until we upload per-screen art of our own; screenArt() is where that
// per-screen override slots back in.
private const val HOURI_ICON_URL =
    "https://raw.githubusercontent.com/PineappleTwilight/komikku-pineapple/master/.github/readme-images/app-icon.png"

private fun screenArt(perScreenUrl: String?) = perScreenUrl ?: HOURI_ICON_URL

// Enum class for standard Rich Presence in-app screens
enum class DiscordScreen(
    @StringRes val text: Int,
    @StringRes val details: Int,
    val imageUrl: String = screenArt(null),
) {
    APP(R.string.app_name, R.string.discord_status_using),
    LIBRARY(R.string.label_library, R.string.discord_status_browsing),
    UPDATES(R.string.label_recent_updates, R.string.discord_status_scrolling),
    HISTORY(R.string.label_recent_manga, R.string.discord_status_scrolling),
    BROWSE(R.string.label_sources, R.string.discord_status_browsing),
    MORE(R.string.label_settings, R.string.discord_status_messing),
    WEBVIEW(R.string.action_web_view, R.string.discord_status_browsing),
    MANGA(R.string.manga, R.string.reading),
}
// <-- AM (DISCORD)
