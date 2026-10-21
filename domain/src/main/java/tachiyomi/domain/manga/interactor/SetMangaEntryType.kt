package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.EntryType
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.entryType
import tachiyomi.domain.manga.model.hasExplicitEntryType
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.model.withEntryType
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Records the reader's content type for one library entry.
 *
 * Passing null clears the choice and hands the entry back to detection, which is not the same as
 * saying "comic": a user who has never expressed a preference and a user who reset to default must
 * not leave the same trace, because the first has never been overruled and the second has been
 * deliberately released.
 *
 * A missing entry is a no-op rather than a crash. The repository's accessor throws on an unknown id,
 * which is right for a caller that believes the entry exists and wrong for an override that must
 * simply do nothing when the entry has been deleted underneath it.
 */
@Inject
class SetMangaEntryType(
    private val mangaRepository: MangaRepository,
) {

    suspend operator fun invoke(mangaId: Long, type: EntryType?): Boolean {
        val manga = mangaRepository.getMangaByIdOrNull(mangaId) ?: return false
        return mangaRepository.update(
            manga.withEntryType(type).toMangaUpdate(),
        )
    }

    /**
     * The type to render this entry as right now.
     *
     * Detection is the host's job rather than this interactor's, because it needs the chapter's
     * format and the archive it lives in — neither of which belongs in a repository read. What this
     * answers is what has been *decided*, which is the part that must never come from a heuristic.
     */
    suspend fun decidedType(mangaId: Long): EntryType? =
        mangaRepository.getMangaByIdOrNull(mangaId)
            ?.takeIf { it.hasExplicitEntryType() }
            ?.entryType()

    private suspend fun MangaRepository.getMangaByIdOrNull(id: Long) = runCatching { getMangaById(id) }.getOrNull()
}
