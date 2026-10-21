package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Keeps status categories in step with a manga's tracker status.
 *
 * Only categories explicitly bound to a status take part. An unbound category is left exactly as it
 * was, so a folder the user named "Reading" for their own reasons is never touched. A manga is
 * moved into the category bound to its current status and taken out of every other bound category;
 * binding the same status to two categories puts it in both.
 *
 * A **subcategory** is only moved within, never into, a branch the manga is already under — see
 * [updateMembership]. A top-level category has no parent to scope by and behaves as above.
 *
 * A null [trackStatus] means the manga is no longer tracked to any real status, which clears it out
 * of every bound category rather than guessing which one it should sit in.
 */
@Inject
class SyncCategoriesWithTrackStatus(
    private val categoryRepository: CategoryRepository,
    private val setMangaCategories: SetMangaCategories,
) {

    suspend fun await(mangaId: Long, trackStatus: Int?) {
        if (mangaId <= 0L) return
        // The whole tree, not just the bound part of it: whether a subcategory may move this manga
        // depends on its parent, and a parent is usually not bound to a status itself.
        val categories = categoryRepository.getAll()
        if (categories.none { it.trackStatus != null }) return

        val current = categoryRepository.getCategoriesByMangaId(mangaId).mapTo(mutableSetOf()) { it.id }
        val previous = current.toSet()
        val updated = updateMembership(current, categories, trackStatus) ?: return
        if (updated.toSet() == previous) return

        logcat(LogPriority.DEBUG) {
            "SyncCategoriesWithTrackStatus: manga $mangaId -> status $trackStatus, categories $updated"
        }
        setMangaCategories.await(mangaId, updated)
    }

    companion object {

        /**
         * Pure so the rules can be tested without a database. Returns null when no category is bound
         * at all, meaning there is nothing for this manga to be moved between.
         *
         * ### Why a subcategory is scoped and a top-level one is not
         *
         * Binding a status to `Shounen → Reading` and to `Josei → Reading` used to put every manga
         * with that status into both, whichever branch it was filed under. The user filing something
         * in Shounen is exactly the statement that it is not in Josei, and a second Reading folder
         * was how the same question got asked twice and answered twice.
         *
         * So a subcategory only takes part when its parent is somewhere the manga already is — or is
         * an ancestor of somewhere it already is, which is the same statement one level further up.
         * Anything outside that scope is not merely not added; it is not *touched*, so a manga keeps
         * whatever it already had in a branch this rule has no business reasoning about.
         *
         * A top-level category keeps the unscoped behaviour deliberately: it has no parent, so
         * "the branch it belongs to" has no answer, and scoping it would silently stop a binding the
         * user set up on a category that was never nested.
         */
        fun updateMembership(
            current: MutableSet<Long>,
            allCategories: List<Category>,
            trackStatus: Int?,
        ): List<Long>? {
            val bound = allCategories.filter { it.trackStatus != null }
            if (bound.isEmpty()) return null

            val scope = scopeOf(current, allCategories)
            for (category in bound) {
                if (!inScope(category, scope)) continue
                if (category.trackStatus == trackStatus) {
                    current.add(category.id)
                } else {
                    current.remove(category.id)
                }
            }
            return current.toList()
        }

        /**
         * The categories this manga is reachable from: the ones it is in, and every ancestor above
         * them. Walking up from the entry rather than testing "is the parent one of the entry's
         * categories" is what makes a manga filed *only* in `Shounen → Reading` still answer for
         * `Shounen → Completed`.
         */
        private fun scopeOf(current: Set<Long>, allCategories: List<Category>): Set<Long> {
            val parentOf = allCategories.associate { it.id to it.parentId }
            val scope = HashSet<Long>(current.size + allCategories.size)
            for (id in current) {
                scope.add(id)
                var parent = parentOf[id] ?: continue
                // `scope.add` doubles as the cycle guard: a tree that points at itself through its
                // own ancestry terminates here rather than hanging the caller. A well-formed tree
                // never meets an id twice, so this costs nothing in the normal case.
                while (parent != UNCATEGORIZED_ID && scope.add(parent)) {
                    parent = parentOf[parent] ?: break
                }
            }
            return scope
        }

        private fun inScope(category: Category, scope: Set<Long>): Boolean =
            category.parentId == UNCATEGORIZED_ID || category.parentId in scope

        private const val UNCATEGORIZED_ID = 0L
    }
}
