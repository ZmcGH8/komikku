package tachiyomi.domain.manga.model

import eu.kanade.tachiyomi.source.model.EntryType

/**
 * What this entry should be rendered as, from the host's point of view.
 *
 * ### Why a resolver rather than a field read
 *
 * Three answers can disagree and there is a precedence between them:
 *
 * 1. the user set it explicitly on this entry,
 * 2. the source declared it via `EntryTypeSource`,
 * 3. nobody said anything and the host has to detect.
 *
 * Only the first may overrule the second. A detection heuristic that silently beats a user's
 * explicit choice is worse than no detection at all, because the user has no way to fix what they
 * cannot see — so the precedence lives in one named function instead of being reassembled at each
 * call site, where it would eventually be got wrong.
 *
 * @see docs/superpowers/specs/2026-10-17-library-entry-types-design.md
 */
object EntryTypeResolver {

    /**
     * The type to render [manga] as, given what the user chose and what the source declared.
     *
     * @param explicit what the user set, or null when they have not expressed a preference.
     * @param declared what the source says via `EntryTypeSource`, or null for no opinion.
     * @param detected the host's heuristic answer. Ignored entirely when either of the above is
     *   non-null — it is the weakest signal of the three and the only one that can be wrong about a
     *   book the user has actually opened.
     */
    fun resolve(explicit: EntryType?, declared: EntryType?, detected: EntryType): EntryType =
        explicit ?: declared ?: detected
}
