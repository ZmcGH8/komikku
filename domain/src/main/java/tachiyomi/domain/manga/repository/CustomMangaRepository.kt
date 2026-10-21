package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tachiyomi.domain.manga.model.CustomMangaInfo

interface CustomMangaRepository {

    fun get(mangaId: Long): CustomMangaInfo?

    fun set(mangaInfo: CustomMangaInfo)

    /**
     * Bulk form of [set].
     *
     * [set] has to copy the whole cache to publish one edit and queues one
     * database transaction per edit, so calling it once per row is quadratic in
     * the number of rows. A restore of a large library can carry an edit for
     * every entry, which made the copy - not the database - the cost. One rebuild
     * and one transaction for the whole list keeps it linear.
     *
     * Semantics match repeated [set] calls: an entry that carries no deviation is
     * removed, and for a repeated id the last occurrence wins.
     */
    fun setAll(mangaInfos: List<CustomMangaInfo>)

    /**
     * Emits after any custom manga info is written, so dependent Flows can
     * re-emit with updated data even though the SQLDelight database hasn't
     * changed.
     */
    val changes: SharedFlow<Unit>
}
