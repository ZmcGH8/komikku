package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.InputStream
import java.time.Instant

/**
 * Leaves this manga's covers on disk now that it has left the library.
 *
 * Deleting them meant the common browse-then-add cycle re-downloaded an image that was already
 * cached. They are dropped later instead, by [CoverCache.pruneOrphanedCovers], once nothing in the
 * library points at them and the retention window has passed.
 *
 * The returned copy bumps `coverLastModified` when a cover was actually there, so a cover visible
 * behind the removal redraws instead of lingering - which is the only reason this returns anything.
 *
 * The files are stamped for a second, independent reason: [CoverCache.pruneOrphanedCovers] ages
 * covers by mtime, so a cover written months before this point would read as overdue the moment it
 * was retained. See [CoverCache.markRetained].
 */
fun Manga.retainCovers(coverCache: CoverCache = globalAppGraph.coverCache): Manga {
    if (isLocal()) return this
    if (!coverCache.hasRetainedCover(this)) return this
    val now = Instant.now().toEpochMilli()
    coverCache.markRetained(this, now)
    return copy(coverLastModified = now)
}

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga = globalAppGraph.updateManga,
    coverCache: CoverCache = globalAppGraph.coverCache,
) {
    if (isLocal()) {
        coverManager.update(toSManga(), stream)
        updateManga.awaitUpdateCoverLastModified(id)
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
        // KMK --> After the write, and only on the cached-custom-cover branch, so this counts
        // covers the user actually set rather than every cover save.
        runCatching { mihon.app.di.globalAppGraph.achievementManager.incrementCounter("custom_covers") }
        runCatching { mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("cover_custom") }
        runCatching {
            mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_daily_extra_7")
            mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_weekly_upload_cover_3")
        }
        // KMK <--
    }
}
