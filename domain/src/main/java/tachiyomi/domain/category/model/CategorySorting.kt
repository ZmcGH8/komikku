package tachiyomi.domain.category.model

/**
 * KMK --> Display-only ordering for the library's category tabs and the category
 * management screen. None of these mutate the stored `sort` column, so a
 * reader who re-sorts by name has not silently rewritten their manual order.
 */
enum class CategorySortOrder {
    MANUAL,
    NAME_ASC,
    NAME_DESC,
    DATE_ADDED_NEWEST,
    DATE_ADDED_OLDEST,
    DATE_MODIFIED_NEWEST,
    DATE_MODIFIED_OLDEST,
    // Appended, so the ordinals above keep matching the values already on disk.
    MOST_ITEMS,
    LEAST_ITEMS,
    ;

    companion object {
        // The management screen persists an Int; keep this the single mapping
        // so the two screens cannot drift apart.
        fun fromStored(value: Int): CategorySortOrder {
            return entries.getOrElse(value) { MANUAL }
        }
    }
}

/**
 * Rows predating tracking carry [Category.DATE_UNKNOWN]. They are placed after
 * dated rows rather than treated as 1970, so a partially-migrated library does
 * not bury every real category at the top of a "newest first" list.
 */
fun List<Category>.sortedForDisplay(sortOrder: CategorySortOrder): List<Category> {
    if (sortOrder == CategorySortOrder.MANUAL) return sortedBy { it.order }
    // The count-based orders need item counts, which a bare Category list does not carry. The
    // caller that has them - the library's grouped tabs - applies them itself, over the groups it
    // just built. Returning manual here rather than falling through to the date branch below is
    // deliberate: otherwise these would silently sort by a date the reader never asked for.
    if (sortOrder == CategorySortOrder.MOST_ITEMS || sortOrder == CategorySortOrder.LEAST_ITEMS) {
        return sortedBy { it.order }
    }

    val byName = when (sortOrder) {
        CategorySortOrder.NAME_DESC -> compareBy(String.CASE_INSENSITIVE_ORDER, Category::name).reversed()
        else -> compareBy(String.CASE_INSENSITIVE_ORDER, Category::name)
    }
    if (sortOrder == CategorySortOrder.NAME_ASC || sortOrder == CategorySortOrder.NAME_DESC) {
        return sortedWith(byName)
    }

    val dateOf: (Category) -> Long = when (sortOrder) {
        CategorySortOrder.DATE_ADDED_NEWEST, CategorySortOrder.DATE_ADDED_OLDEST -> Category::dateAdded
        else -> Category::dateModified
    }
    val byDate: Comparator<Category> =
        if (sortOrder == CategorySortOrder.DATE_ADDED_NEWEST ||
            sortOrder == CategorySortOrder.DATE_MODIFIED_NEWEST
        ) {
            compareByDescending(dateOf)
        } else {
            compareBy(dateOf)
        }

    val dated = mutableListOf<Category>()
    val undated = mutableListOf<Category>()
    for (category in this) {
        if (dateOf(category) == Category.DATE_UNKNOWN) undated += category else dated += category
    }
    return dated.sortedWith(byDate) + undated.sortedBy { it.order }
}

/**
 * [sortedForDisplay] plus the two count-based modes, for the callers that hold the counts.
 *
 * The count modes have to be applied by whoever knows how many entries sit in each category, which
 * a bare [Category] list does not carry - so without [counts] this defers to [sortedForDisplay] and
 * those two stay on stored order rather than silently sorting by a date nobody asked for.
 *
 * A category absent from [counts] counts as empty.
 */
fun List<Category>.sortedForDisplay(
    sortOrder: CategorySortOrder,
    counts: Map<Long, Int>?,
): List<Category> {
    val byCount = counts ?: return sortedForDisplay(sortOrder)
    return when (sortOrder) {
        CategorySortOrder.MOST_ITEMS -> sortedByDescending { byCount[it.id] ?: 0 }
        CategorySortOrder.LEAST_ITEMS -> sortedBy { byCount[it.id] ?: 0 }
        else -> sortedForDisplay(sortOrder)
    }
}
