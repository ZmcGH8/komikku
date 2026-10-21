package tachiyomi.domain.library.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR

object LibraryGroup {

    const val BY_DEFAULT = 0
    const val BY_SOURCE = 1
    const val BY_STATUS = 2
    const val BY_TRACK_STATUS = 3
    const val UNGROUPED = 4

    // KMK -->
    // Custom build (high values to stay clear of upstream)
    /** Tabs for broad categories ([tachiyomi.domain.category.model.CategoryGroup]) */
    const val BY_CATEGORY_GROUP = 20

    /** One tab per tag */
    const val BY_TAG = 21

    /** One tab per author */
    const val BY_AUTHOR = 22

    /** One tab per scanlator (or source, for chapters without a scanlator) */
    const val BY_SCANLATOR = 23

    /** One tab per artist */
    const val BY_ARTIST = 24

    /** One tab per studio (from the "Credits" in the entry's description) */
    const val BY_STUDIO = 25
    // KMK <--

    fun groupTypeStringRes(type: Int): StringResource {
        return when (type) {
            BY_STATUS -> MR.strings.status
            BY_SOURCE -> MR.strings.label_sources
            BY_TRACK_STATUS -> SYMR.strings.tracking_status
            UNGROUPED -> SYMR.strings.ungrouped
            else -> MR.strings.categories
        }
    }
}
