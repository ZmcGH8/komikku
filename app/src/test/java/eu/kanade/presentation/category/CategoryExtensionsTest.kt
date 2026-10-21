package eu.kanade.presentation.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategorySortOrder

@Execution(ExecutionMode.CONCURRENT)
class CategoryExtensionsTest {

    private fun category(
        id: Long,
        name: String,
        parentId: Long = 0L,
        order: Long = id,
    ) = Category(
        id = id,
        name = name,
        order = order,
        flags = 0,
        hidden = false,
        parentId = parentId,
    )

    // The library's filter list: every root, then its subcategories, so a subcategory never floats
    // up next to an unrelated root. The stored order of the subcategories is deliberately not
    // alphabetical, so each mode below produces a different list.
    private val tree = listOf(
        category(1, "zeta", order = 1),
        category(2, "alpha", order = 2),
        category(3, "zeta sub", parentId = 1, order = 6),
        category(4, "alpha sub", parentId = 1, order = 4),
        category(5, "middle sub", parentId = 2, order = 5),
    )

    @Test
    fun `manual order keeps each subcategory under its root`() {
        tree.sortedByHierarchy().map { it.name } shouldBe listOf(
            "zeta",
            "alpha sub",
            "zeta sub",
            "alpha",
            "middle sub",
        )
    }

    @Test
    fun `name order sorts the roots`() {
        tree.sortedByHierarchy(CategorySortOrder.NAME_ASC).first() shouldBe category(2, "alpha", order = 2)
    }

    @Test
    fun `name order sorts the subcategories under their root too`() {
        val ordered = tree.sortedByHierarchy(CategorySortOrder.NAME_ASC)

        // A root with two subcategories keeps them adjacent, and in name order within themselves.
        ordered.windowed(3).first().map { it.name } shouldBe listOf("alpha", "middle sub", "zeta")
        ordered.first { it.name == "zeta" }.let { zeta ->
            ordered.indexOf(zeta) shouldBe 2
            ordered[3].name shouldBe "alpha sub"
            ordered[4].name shouldBe "zeta sub"
        }
    }

    @Test
    fun `name order descending reverses both levels`() {
        tree.sortedByHierarchy(CategorySortOrder.NAME_DESC).map { it.name } shouldBe listOf(
            "zeta",
            "zeta sub",
            "alpha sub",
            "alpha",
            "middle sub",
        )
    }

    @Test
    fun `a subcategory whose root is gone stays selectable`() {
        // Root 7 is not in the list at all - the row it pointed at was deleted - so the
        // subcategory has no root to be attached to and would otherwise be dropped.
        val orphaned = category(9, "orphan sub", parentId = 7, order = 1)

        (tree + orphaned).sortedByHierarchy(CategorySortOrder.NAME_ASC).last() shouldBe orphaned
    }
}
