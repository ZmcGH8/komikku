package tachiyomi.domain.category.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class CategorySortingTest {

    private fun category(
        id: Long,
        name: String,
        order: Long = id,
        dateAdded: Long = Category.DATE_UNKNOWN,
        dateModified: Long = Category.DATE_UNKNOWN,
    ) = Category(
        id = id,
        name = name,
        order = order,
        flags = 0,
        hidden = false,
        dateAdded = dateAdded,
        dateModified = dateModified,
    )

    private fun names(categories: List<Category>) = categories.map { it.name }

    @Test
    fun `manual mode returns the stored order untouched`() {
        val categories = listOf(category(1, "Beta", order = 2), category(2, "Alpha", order = 1))

        names(categories.sortedForDisplay(CategorySortOrder.MANUAL)) shouldBe listOf("Alpha", "Beta")
    }

    @Test
    fun `name sorting is case insensitive`() {
        val categories = listOf(category(1, "banana"), category(2, "Apple"), category(3, "cherry"))

        names(categories.sortedForDisplay(CategorySortOrder.NAME_ASC)) shouldBe listOf("Apple", "banana", "cherry")
        names(categories.sortedForDisplay(CategorySortOrder.NAME_DESC)) shouldBe listOf("cherry", "banana", "Apple")
    }

    @Test
    fun `date added newest first`() {
        val categories = listOf(
            category(1, "old", dateAdded = 100),
            category(2, "new", dateAdded = 300),
            category(3, "mid", dateAdded = 200),
        )

        names(categories.sortedForDisplay(CategorySortOrder.DATE_ADDED_NEWEST)) shouldBe listOf("new", "mid", "old")
        names(categories.sortedForDisplay(CategorySortOrder.DATE_ADDED_OLDEST)) shouldBe listOf("old", "mid", "new")
    }

    @Test
    fun `date modified is read independently of date added`() {
        val categories = listOf(
            category(1, "createdFirst", dateAdded = 100, dateModified = 300),
            category(2, "modifiedLast", dateAdded = 300, dateModified = 100),
        )

        names(categories.sortedForDisplay(CategorySortOrder.DATE_MODIFIED_NEWEST)) shouldBe
            listOf("createdFirst", "modifiedLast")
    }

    @Test
    fun `undated rows sort after dated rows instead of counting as 1970`() {
        val categories = listOf(
            category(1, "legacy"),
            category(2, "recent", dateAdded = 500),
        )

        names(categories.sortedForDisplay(CategorySortOrder.DATE_ADDED_NEWEST)) shouldBe listOf("recent", "legacy")
    }

    @Test
    fun `undated rows keep stored order among themselves`() {
        val categories = listOf(
            category(1, "first", order = 2),
            category(2, "second", order = 1),
        )

        names(categories.sortedForDisplay(CategorySortOrder.DATE_ADDED_NEWEST)) shouldBe listOf("second", "first")
    }

    @Test
    fun `an entirely undated library does not collapse into a date tie`() {
        val categories = listOf(
            category(1, "Beta", order = 2),
            category(2, "Alpha", order = 1),
        )

        names(categories.sortedForDisplay(CategorySortOrder.DATE_ADDED_OLDEST)) shouldBe listOf("Alpha", "Beta")
    }

    @Test
    fun `stored mode out of range falls back to manual`() {
        CategorySortOrder.fromStored(99) shouldBe CategorySortOrder.MANUAL
        CategorySortOrder.fromStored(CategorySortOrder.NAME_DESC.ordinal) shouldBe CategorySortOrder.NAME_DESC
    }

    @Test
    fun `appending the count based modes did not renumber the stored ones`() {
        CategorySortOrder.values().toList() shouldBe listOf(
            CategorySortOrder.MANUAL,
            CategorySortOrder.NAME_ASC,
            CategorySortOrder.NAME_DESC,
            CategorySortOrder.DATE_ADDED_NEWEST,
            CategorySortOrder.DATE_ADDED_OLDEST,
            CategorySortOrder.DATE_MODIFIED_NEWEST,
            CategorySortOrder.DATE_MODIFIED_OLDEST,
            CategorySortOrder.MOST_ITEMS,
            CategorySortOrder.LEAST_ITEMS,
        )
    }

    @Test
    fun `count based modes keep the stored order here since a category list carries no counts`() {
        val categories = listOf(category(1, "Beta", order = 2), category(2, "Alpha", order = 1))

        // The library reorders the grouped tabs itself, where the counts exist. Falling through to
        // the date branch instead would sort by a date the reader never asked for.
        names(categories.sortedForDisplay(CategorySortOrder.MOST_ITEMS)) shouldBe listOf("Alpha", "Beta")
        names(categories.sortedForDisplay(CategorySortOrder.LEAST_ITEMS)) shouldBe listOf("Alpha", "Beta")
    }

    @Test
    fun `count based modes order by the supplied counts`() {
        val categories = listOf(category(1, "small"), category(2, "big"), category(3, "mid"))
        val counts = mapOf(1L to 2, 2L to 40, 3L to 9)

        names(categories.sortedForDisplay(CategorySortOrder.MOST_ITEMS, counts)) shouldBe
            listOf("big", "mid", "small")
        names(categories.sortedForDisplay(CategorySortOrder.LEAST_ITEMS, counts)) shouldBe
            listOf("small", "mid", "big")
    }

    @Test
    fun `a category missing from the counts is treated as empty`() {
        val categories = listOf(category(1, "untracked"), category(2, "tracked"))
        val counts = mapOf(2L to 3)

        names(categories.sortedForDisplay(CategorySortOrder.LEAST_ITEMS, counts)) shouldBe
            listOf("untracked", "tracked")
        names(categories.sortedForDisplay(CategorySortOrder.MOST_ITEMS, counts)) shouldBe
            listOf("tracked", "untracked")
    }

    @Test
    fun `passing counts leaves the non count modes alone`() {
        val categories = listOf(category(1, "banana"), category(2, "Apple"), category(3, "cherry"))
        val counts = mapOf(1L to 9, 2L to 1, 3L to 5)

        // The name order must win over the counts, or the two screens would disagree again.
        names(categories.sortedForDisplay(CategorySortOrder.NAME_ASC, counts)) shouldBe
            listOf("Apple", "banana", "cherry")
    }
}
