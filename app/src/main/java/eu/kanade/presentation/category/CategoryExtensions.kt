package eu.kanade.presentation.category

import android.content.Context
import androidx.compose.runtime.Composable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategorySortOrder
import tachiyomi.domain.category.model.sortedForDisplay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val Category.visualName: String
    @Composable
    get() = when {
        isSystemCategory -> stringResource(MR.strings.label_default)
        else -> name
    }

fun Category.visualName(context: Context): String =
    when {
        isSystemCategory -> context.stringResource(MR.strings.label_default)
        else -> name
    }

// KMK -->
/**
 * Returns the categories ordered for hierarchical display: every root category
 * followed immediately by its subcategories. Each level is ordered by [sortOrder],
 * so a reader who picked "name A → Z" in the category manager gets the same order
 * here - on the roots and on the subcategories under them - instead of the stored
 * order in one list and the chosen one in the other. Subcategories whose root no
 * longer exists are appended at the end so they remain selectable.
 *
 * The two count-based modes are the exception, and are left to the stored order:
 * a bare list of categories carries no counts, so [sortedForDisplay] has nothing
 * to rank by. Only the library's grouped tabs and its folder layout have counts,
 * and they order their own lists.
 */
fun List<Category>.sortedByHierarchy(
    sortOrder: CategorySortOrder = CategorySortOrder.MANUAL,
): List<Category> {
    val roots = filter { it.parentId == 0L }.sortedForDisplay(sortOrder)
    val subcategories = filter { it.parentId != 0L }
    return buildList {
        val attached = mutableSetOf<Long>()
        roots.forEach { root ->
            add(root)
            subcategories.filter { it.parentId == root.id }
                .sortedForDisplay(sortOrder)
                .forEach {
                    add(it)
                    attached += it.id
                }
        }
        subcategories.filterNot { it.id in attached }
            .sortedForDisplay(sortOrder)
            .forEach(::add)
    }
}

/**
 * Label for a category shown in flat selection lists, prefixing subcategories
 * with an indent marker so their nesting under the preceding root category is
 * visible in plain-text rows.
 */
val Category.hierarchicalVisualName: String
    @Composable
    get() = if (parentId == 0L) visualName else "└ $visualName"
// KMK <--
