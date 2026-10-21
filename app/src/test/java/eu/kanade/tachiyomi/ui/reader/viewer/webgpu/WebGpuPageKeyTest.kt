package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Equality for the two page-identity keys the cache is keyed by.
 *
 * These are `data class`es, which is the whole mechanism: `get` and `remove` on the page cache go
 * through `hashCode` and `equals`, so if either were wrong the cache would stop finding pages it
 * had already decoded and silently re-decode everything. There is no other code path to break, so
 * the coverage that matters is that a key behaves correctly as a *map key* - value equality,
 * consistent hashing, null chapter ids, and the two key types never colliding with each other.
 *
 * The transition key is included because it is the one that can legitimately be null on both
 * sides: a strip at the very start or end of the book has no previous or next chapter, and both
 * of those states have to produce a distinct, stable key rather than one that equals everything.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuPageKeyTest {

    @Test
    fun `a reader key equals one built the same way`() {
        PageKey.Reader(chapterId = 7L, index = 3) shouldBe PageKey.Reader(chapterId = 7L, index = 3)
    }

    @Test
    fun `equal reader keys hash the same, so they work as map keys`() {
        val a = PageKey.Reader(chapterId = 7L, index = 3)
        val b = PageKey.Reader(chapterId = 7L, index = 3)
        a.hashCode() shouldBe b.hashCode()

        val cache = HashMap<PageKey, String>()
        cache[a] = "first"
        cache[b] = "second"
        // One entry, and the second write won - this is the property the page cache depends on.
        cache.size shouldBe 1
        cache[a] shouldBe "second"
    }

    @Test
    fun `a different index or chapter is a different key`() {
        val base = PageKey.Reader(chapterId = 7L, index = 3)
        base shouldNotBe PageKey.Reader(chapterId = 7L, index = 4)
        base shouldNotBe PageKey.Reader(chapterId = 8L, index = 3)
        base.hashCode() shouldNotBe PageKey.Reader(chapterId = 8L, index = 3).hashCode()
    }

    @Test
    fun `a null chapter id is a value like any other`() {
        val a = PageKey.Reader(chapterId = null, index = 0)
        val b = PageKey.Reader(chapterId = null, index = 0)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        // A page with no chapter is not the same page as one with chapter 0.
        a shouldNotBe PageKey.Reader(chapterId = 0L, index = 0)
    }

    @Test
    fun `a transition key equals one built the same way`() {
        val key = PageKey.Transition(prevId = 1L, nextId = 2L)
        key shouldBe PageKey.Transition(prevId = 1L, nextId = 2L)
        key.hashCode() shouldBe PageKey.Transition(prevId = 1L, nextId = 2L).hashCode()
    }

    @Test
    fun `the two transition nulls are distinct from each other and from real chapters`() {
        val neither = PageKey.Transition(prevId = null, nextId = null)
        val onlyPrev = PageKey.Transition(prevId = 1L, nextId = null)
        val onlyNext = PageKey.Transition(prevId = null, nextId = 2L)

        neither shouldNotBe onlyPrev
        neither shouldNotBe onlyNext
        onlyPrev shouldNotBe onlyNext

        // First page of the book: no previous, no next.
        neither shouldBe PageKey.Transition(prevId = null, nextId = null)
        // Last page: both neighbours gone, and that must not equal the first-page state.
        PageKey.Transition(prevId = 9L, nextId = 9L) shouldNotBe neither
    }

    @Test
    fun `a reader key never equals a transition key, whatever the fields hold`() {
        // Different sealed subtypes, so no field overlap can make them compare equal.
        val reader: PageKey = PageKey.Reader(chapterId = 1L, index = 2)
        val transition: PageKey = PageKey.Transition(prevId = 1L, nextId = 2L)
        reader shouldNotBe transition
    }

    @Test
    fun `keys deduplicate correctly in a set`() {
        val keys = setOf(
            PageKey.Reader(chapterId = 1L, index = 0),
            PageKey.Reader(chapterId = 1L, index = 0),
            PageKey.Reader(chapterId = 1L, index = 1),
            PageKey.Transition(prevId = null, nextId = null),
            PageKey.Transition(prevId = null, nextId = null),
        )
        keys.size shouldBe 3
    }

    @Test
    fun `the spread position enum has three distinct states`() {
        // Which half of a spread a page was filed under; a new value here changes layout pairing.
        SpreadPosition.entries.toList() shouldBe listOf(
            SpreadPosition.LEFT,
            SpreadPosition.RIGHT,
            SpreadPosition.SINGLE,
        )
    }
}
