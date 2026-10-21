package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val TITLE_PRIORITIES = listOf("en", "ja-Latn", "ja", "ko-Latn", "ko", "zh-Latn", "zh")

@Serializable
data class MangaBakaItemResult(
    val data: MangaBakaItem,
)

@Serializable
data class MangaBakaSearchResult(
    val data: List<MangaBakaItem>,
)

@Serializable
data class MangaBakaItem(
    val id: Long,
    val cover: MangaBakaCover,
    val authors: List<String>?,
    val artists: List<String>?,
    val tags: List<String>? = null,
    val description: String?,
    val published: MangaBakaPublishData,
    val status: String,
    val type: String,
    val rating: Double?,
    val titles: List<MangaBakaItemTitle>?,
    // KMK --> series-detail payloads carry relationships_v2 (absent on search items), and every
    // payload carries the series' own chapter count as a JSON *string* ("72"), null while the
    // site has none recorded — see [totalChaptersCount].
    @SerialName("relationships_v2")
    val relationshipsV2: List<MangaBakaRelationship>? = null,
    @SerialName("total_chapters")
    val totalChapters: String? = null,
    // KMK <--
) {
    fun chooseBestTitle(): String {
        val bestTitlePerLanguage = TITLE_PRIORITIES.associateWith { lang ->
            titles?.filter { it.language == lang }
                ?.minByOrNull {
                    when {
                        it.isPrimary -> 0
                        "official" in it.traits -> 1
                        "native" in it.traits -> 2
                        else -> 3
                    }
                }
        }

        return TITLE_PRIORITIES
            .firstNotNullOfOrNull { bestTitlePerLanguage[it]?.title }
            ?: titles?.firstOrNull()?.title
            ?: "ID: $id - Could not find name! (report on the MangaBaka Discord)"
    }

    // KMK -->
    /**
     * The series' chapter count, or 0 when the site has not recorded one.
     *
     * This is the number the tracker row's "chapters x / y" is measured against, and the one
     * [MangaBaka.update]'s completion rule compares the reader's progress to — reading the last
     * chapter of a series only marks it completed when the tracker knows which chapter is the
     * last. Parsed through a double because the payload carries the count as a *string*, and 0 is
     * the tracker model's "unknown" — a value that does not parse must land there, not throw.
     */
    fun totalChaptersCount(): Long = totalChapters?.toDoubleOrNull()?.toLong() ?: 0L
    // KMK <--
}

@Serializable
data class MangaBakaRelationship(
    @SerialName("to_series_id")
    val toSeriesId: Long,
    @SerialName("relation_type")
    val relationType: String,
)

// KMK <--

@Serializable
data class MangaBakaCover(
    val raw: MangaBakaRawCover? = null,
    val x150: MangaBakaScaledCover? = null,
    val x250: MangaBakaScaledCover? = null,
    val x350: MangaBakaScaledCover? = null,
) {
    // KMK --> largest-first cover pick, shared by search and the sequel/prequel cards
    fun bestUrl(): String? = raw?.url ?: x350?.x1 ?: x250?.x1 ?: x150?.x1
    // KMK <--
}

@Serializable
data class MangaBakaScaledCover(
    val x1: String?,
    val x2: String? = null,
    val x3: String? = null,
)

@Serializable
data class MangaBakaRawCover(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val format: String? = null,
)

@Serializable
data class MangaBakaPublishData(
    @SerialName("start_date")
    val startDate: String?,
)

@Serializable
data class MangaBakaItemTitle(
    val language: String,
    val traits: List<String>,
    val title: String,
    @SerialName("is_primary")
    val isPrimary: Boolean = false,
)
