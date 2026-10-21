package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import exh.source.ExhPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.source.local.isLocal
import java.util.Collections

@Inject
class GetSourcesWithFavoriteCount(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    // KMK --> Defaulted rather than required: this interactor is built two ways, by Metro and by the
    // legacy Injekt `DomainModule`, and `ExhPreferences` is only reachable through the graph. Same
    // arrangement as `BackupFileValidator` and `MangaRestorer`.
    private val exhPreferences: ExhPreferences = globalAppGraph.exhPreferences,
) {

    fun subscribe(): Flow<List<Pair<Source, Long>>> {
        return combine(
            preferences.migrationSortingDirection().changes(),
            preferences.migrationSortingMode().changes(),
            // KMK --> Re-read when the language settings change, so switching a language off takes
            // its source out of this list without the screen having to be re-entered.
            exhPreferences.exhSettingsLanguages().changes(),
            // KMK <--
            repository.getSourcesWithFavoriteCount(),
        ) { direction, mode, _, list ->
            list
                .filterNot { it.first.isLocal() }
                // KMK --> Every E-Hentai language is its own source, all of them live whenever the
                // source is on, so an entry filed under a language the user turned off still puts a
                // row here. The picker then offers fifteen E-Hentai sources for a user who has one.
                .filterNot { !exhPreferences.isEHentaiSourceEnabled(it.first.id) }
                // KMK <--
                .sortedWith(sortFn(direction, mode))
        }
    }

    private fun sortFn(
        direction: SetMigrateSorting.Direction,
        sorting: SetMigrateSorting.Mode,
    ): java.util.Comparator<Pair<Source, Long>> {
        val sortFn: (Pair<Source, Long>, Pair<Source, Long>) -> Int = { a, b ->
            when (sorting) {
                SetMigrateSorting.Mode.ALPHABETICAL -> {
                    when {
                        a.first.isStub && !b.first.isStub -> -1
                        b.first.isStub && !a.first.isStub -> 1
                        else -> a.first.name.lowercase().compareToWithCollator(b.first.name.lowercase())
                    }
                }
                SetMigrateSorting.Mode.TOTAL -> {
                    when {
                        a.first.isStub && !b.first.isStub -> -1
                        b.first.isStub && !a.first.isStub -> 1
                        else -> a.second.compareTo(b.second)
                    }
                }
            }
        }

        return when (direction) {
            SetMigrateSorting.Direction.ASCENDING -> Comparator(sortFn)
            SetMigrateSorting.Direction.DESCENDING -> Collections.reverseOrder(sortFn)
        }
    }
}
