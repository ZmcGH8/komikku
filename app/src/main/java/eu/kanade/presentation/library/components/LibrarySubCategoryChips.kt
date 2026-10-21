package eu.kanade.presentation.library.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import eu.kanade.presentation.category.visualName
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
/**
 * Custom build: a row of chips under a broad-category tab to show all of it or a single sub-category.
 * Adapted from Houri's subcategory row (github.com/PineappleTwilight/houri, Apache-2.0).
 */
@Composable
internal fun LibrarySubCategoryChips(
    subCategories: List<Category>,
    selectedSubCategoryId: Long?,
    onSelectSubCategory: (Long?) -> Unit,
) {
    if (subCategories.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        SubCategoryChip(
            selected = selectedSubCategoryId == null,
            onClick = { onSelectSubCategory(null) },
            label = stringResource(MR.strings.all),
        )
        subCategories.forEach { subCategory ->
            SubCategoryChip(
                selected = selectedSubCategoryId == subCategory.id,
                onClick = { onSelectSubCategory(subCategory.id) },
                label = subCategory.visualName,
            )
        }
    }
}

@Composable
private fun SubCategoryChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}
// KMK <--
