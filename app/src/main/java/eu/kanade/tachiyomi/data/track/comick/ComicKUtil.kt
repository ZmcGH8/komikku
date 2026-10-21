package eu.kanade.tachiyomi.data.track.comick

import java.util.Base64

// Deterministic en/decoding HID <-> Long for our internal storage.
// Comick HIDs are base64url-encoded 48-bit integers; decoding is lossless and
// collision-safe, never a hash of the title (per Comick's tracker guidance).
internal fun String.asHidToLong(): Long {
    require(this.length == 8) { "HID must be 8 characters for decoding, got ${this.length}: $this" }
    return Base64.getUrlDecoder()
        .decode(this)
        .fold(0L) { acc, b ->
            (acc shl 8) or (b.toLong() and 0xFF)
        }
}

internal fun Long.toHid(): String {
    require(this in 0 until (1L shl 48)) { "Long not in 48-bit range for HID conversion: $this" }
    return Base64.getUrlEncoder()
        .encodeToString(
            ByteArray(6) {
                (this shr (8 * (5 - it))).toByte()
            },
        )
}

internal fun Long.toApiListStatus() = when (this) {
    ComicK.READING -> 1
    ComicK.COMPLETED -> 2
    ComicK.ON_HOLD -> 3
    ComicK.DROPPED -> 4
    ComicK.PLAN_TO_READ -> 5
    else -> throw NotImplementedError("Unknown status: $this")
}

internal fun Long.fromApiListStatus() = when (this) {
    1L -> ComicK.READING
    2L -> ComicK.COMPLETED
    3L -> ComicK.ON_HOLD
    4L -> ComicK.DROPPED
    5L -> ComicK.PLAN_TO_READ
    else -> throw NotImplementedError("Unknown status: $this")
}

class ComickMissingScopesException(message: String) : Exception(message)
