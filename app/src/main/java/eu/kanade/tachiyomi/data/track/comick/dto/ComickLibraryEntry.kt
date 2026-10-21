package eu.kanade.tachiyomi.data.track.comick.dto

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.comick.asHidToLong
import eu.kanade.tachiyomi.data.track.comick.fromApiListStatus
import kotlinx.serialization.Serializable

@Serializable
data class ComickLibraryEntry(
    val data: ComickLibraryEntryItem,
) {
    fun toTrack(trackerId: Long): Track {
        return Track.create(trackerId).apply {
            remote_id = data.hid.asHidToLong()
            title = data.title
            status = data.status.fromApiListStatus()
            last_chapter_read = data.progress?.number?.toDoubleOrNull() ?: 0.0
            // KMK --> reads may retain decimal imported scores; store raw, clamp on write
            score = data.rating ?: 0.0
            // KMK <--
        }
    }
}

@Serializable
data class ComickLibraryEntryItem(
    val hid: String,
    val title: String,
    /** Follow status: 1 reading, 2 completed, 3 on hold, 4 dropped, 5 plan to read. */
    val status: Long,
    val progress: ComickLibraryEntryProgress? = null,
    val rating: Double? = null,
)

@Serializable
data class ComickLibraryEntryProgress(
    val number: String? = null,
)
