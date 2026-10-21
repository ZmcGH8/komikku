package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.source.interactor.LibraryMatcher
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import kotlinx.collections.immutable.persistentListOf
import mihon.app.di.globalAppGraph
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsBrowseScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsBrowseScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { globalAppGraph.sourcePreferences }
        val getExtensionStoreCountAsFlow = remember { globalAppGraph.getExtensionStoreCountAsFlow }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        // SY -->
        val uiPreferences = remember { globalAppGraph.uiPreferences }
        // SY <--
        // KMK --> Houri+: review titles hidden by a name match
        val hiddenMatches by sourcePreferences.hiddenLibraryMatches().collectAsState()
        var showHiddenMatches by remember { mutableStateOf(false) }
        if (showHiddenMatches) {
            HiddenLibraryMatchesDialog(
                sourcePreferences = sourcePreferences,
                onDismissRequest = { showHiddenMatches = false },
            )
        }
        // Titles that look like library titles and wait for the user to confirm
        val reviewItems by sourcePreferences.libraryMatchReview().collectAsState()
        var showReview by remember { mutableStateOf(false) }
        if (showReview) {
            LibraryMatchReviewDialog(
                sourcePreferences = sourcePreferences,
                onOpenEntry = { mangaId ->
                    showReview = false
                    navigator.push(MangaScreen(mangaId))
                },
                onDismissRequest = { showReview = false },
            )
        }
        // KMK <--
        return listOf(
            // SY -->
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = persistentListOf(
                    // KMK -->
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.relatedMangas(),
                        title = stringResource(KMR.strings.pref_source_related_mangas),
                        subtitle = stringResource(KMR.strings.pref_source_related_mangas_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.expandRelatedMangas(),
                        title = stringResource(KMR.strings.pref_expand_related_mangas),
                        subtitle = stringResource(KMR.strings.pref_expand_related_mangas_summary),
                        dependsOn = PreferenceDependency(sourcePreferences.relatedMangas()),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.relatedMangasInOverflow(),
                        dependsOn = PreferenceDependency(uiPreferences.expandRelatedMangas(), invert = true),
                        title = stringResource(KMR.strings.put_related_mangas_in_overflow),
                        subtitle = stringResource(KMR.strings.put_related_mangas_in_overflow_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.showHomeOnRelatedMangas(),
                        title = stringResource(KMR.strings.pref_show_home_on_related_mangas),
                        subtitle = stringResource(KMR.strings.pref_show_home_on_related_mangas_summary),
                        dependsOn = PreferenceDependency(sourcePreferences.relatedMangas()),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.showSequelPrequel(),
                        title = stringResource(KMR.strings.pref_sequel_prequel),
                        subtitle = stringResource(KMR.strings.pref_sequel_prequel_summary),
                    ),
                    // KMK <--
                    run {
                        val count by sourcePreferences.sourcesTabCategories().collectAsState()
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.action_edit_categories),
                            subtitle = pluralStringResource(MR.plurals.num_categories, count.size, count.size),
                            onClick = {
                                navigator.push(SourceCategoryScreen())
                            },
                        )
                    },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.sourcesTabCategoriesFilter(),
                        title = stringResource(SYMR.strings.pref_source_source_filtering),
                        subtitle = stringResource(SYMR.strings.pref_source_source_filtering_summery),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.useNewSourceNavigation(),
                        title = stringResource(SYMR.strings.pref_source_navigation),
                        subtitle = stringResource(SYMR.strings.pref_source_navigation_summery),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.allowLocalSourceHiddenFolders(),
                        title = stringResource(SYMR.strings.pref_local_source_hidden_folders),
                        subtitle = stringResource(SYMR.strings.pref_local_source_hidden_folders_summery),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.feed),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.hideFeedTab(),
                        title = stringResource(SYMR.strings.pref_hide_feed),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.feedTabInFront(),
                        title = stringResource(SYMR.strings.pref_feed_position),
                        subtitle = stringResource(SYMR.strings.pref_feed_position_summery),
                        dependsOn = PreferenceDependency(uiPreferences.hideFeedTab(), invert = true),
                    ),
                    // KMK -->
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryFeedItems(),
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    // KMK <--
                ),
            ),
            // SY <--
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems(),
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    // KMK --> Houri+
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideLibraryMatches(),
                        title = stringResource(KMR.strings.pref_custom_hide_library_matches),
                        subtitle = stringResource(KMR.strings.pref_custom_hide_library_matches_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.aiLibraryMatching(),
                        title = stringResource(KMR.strings.pref_custom_ai_library_matching),
                        subtitle = stringResource(KMR.strings.pref_custom_ai_library_matching_summary),
                        dependsOn = PreferenceDependency(sourcePreferences.hideLibraryMatches()),
                        mtlOnly = true,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_custom_match_review),
                        subtitle = stringResource(KMR.strings.pref_custom_match_review_summary, reviewItems.size),
                        onClick = { showReview = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_custom_hidden_matches),
                        subtitle = stringResource(KMR.strings.pref_custom_hidden_matches_summary, hiddenMatches.size),
                        onClick = { showHiddenMatches = true },
                    ),
                    // KMK <--
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource(),
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
        )
    }
}

// KMK -->
@Composable
private fun HiddenLibraryMatchesDialog(
    sourcePreferences: SourcePreferences,
    onDismissRequest: () -> Unit,
) {
    val sourceManager = remember { globalAppGraph.sourceManager }
    val entries by sourcePreferences.hiddenLibraryMatches().collectAsState()
    val matches = remember(entries) {
        LibraryMatcher.parseHiddenMatches(entries)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryMatcher.HiddenMatch::libraryTitle))
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.pref_custom_hidden_matches)) },
        text = {
            if (matches.isEmpty()) {
                Text(text = stringResource(KMR.strings.pref_custom_hidden_matches_empty))
            } else {
                LazyColumn {
                    items(
                        items = matches,
                        key = { "${it.sourceId}\t${it.url}" },
                    ) { match ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = match.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        KMR.strings.pref_custom_hidden_match_detail,
                                        sourceManager.getOrStub(match.sourceId).name,
                                        match.libraryTitle,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { LibraryMatcher.showAgain(sourcePreferences, match) }) {
                                Text(text = stringResource(KMR.strings.action_custom_show_again))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}

/** Houri+: titles that look like library titles but don't match on both name and author, for the user to decide. */
@Composable
private fun LibraryMatchReviewDialog(
    sourcePreferences: SourcePreferences,
    onOpenEntry: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sourceManager = remember { globalAppGraph.sourceManager }
    val entries by sourcePreferences.libraryMatchReview().collectAsState()
    val reviewList = remember(entries) {
        LibraryMatcher.parseReviewItems(entries)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryMatcher.ReviewItem::libraryTitle))
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.pref_custom_match_review)) },
        text = {
            if (reviewList.isEmpty()) {
                Text(text = stringResource(KMR.strings.pref_custom_match_review_empty))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(
                        items = reviewList,
                        key = { "${it.sourceId}\t${it.url}" },
                    ) { item ->
                        val sourceManga by produceState<Manga?>(null, item.mangaId) {
                            value = item.mangaId?.let { globalAppGraph.getManga.await(it) }
                        }
                        val libraryManga by produceState<Manga?>(null, item.libraryId) {
                            value = globalAppGraph.getManga.await(item.libraryId)
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MangaCover.Book(
                                    data = sourceManga?.asMangaCover(),
                                    modifier = Modifier.width(56.dp),
                                    onClick = item.mangaId?.let { id -> { onOpenEntry(id) } },
                                )
                                MangaCover.Book(
                                    data = libraryManga?.asMangaCover(),
                                    modifier = Modifier.width(56.dp),
                                    onClick = { onOpenEntry(item.libraryId) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            sourceManager.getOrStub(item.sourceId).name,
                                            item.sourceAuthor.takeIf { it.isNotBlank() }
                                                ?.let { stringResource(KMR.strings.custom_match_review_by, it) },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            stringResource(KMR.strings.custom_match_review_in_library, item.libraryTitle),
                                            item.libraryAuthor.takeIf { it.isNotBlank() }
                                                ?.let { stringResource(KMR.strings.custom_match_review_by, it) },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = stringResource(reviewReason(item.reason)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { LibraryMatcher.rejectReview(sourcePreferences, item) }) {
                                    Text(text = stringResource(KMR.strings.action_custom_match_different))
                                }
                                TextButton(onClick = { LibraryMatcher.confirmReview(sourcePreferences, item) }) {
                                    Text(text = stringResource(KMR.strings.action_custom_match_same))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}

private fun reviewReason(reason: String): StringResource = when (reason) {
    LibraryMatcher.REASON_SAME_NAME_OTHER_AUTHOR -> KMR.strings.custom_match_review_reason_author
    LibraryMatcher.REASON_SAME_NAME_NO_AUTHOR -> KMR.strings.custom_match_review_reason_noauthor
    LibraryMatcher.REASON_SIMILAR_NAME_SAME_AUTHOR -> KMR.strings.custom_match_review_reason_similar
    else -> KMR.strings.custom_match_review_reason_ai
}
// KMK <--
