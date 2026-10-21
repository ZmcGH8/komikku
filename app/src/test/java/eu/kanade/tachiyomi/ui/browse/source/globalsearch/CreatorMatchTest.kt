package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.Manga

@Execution(ExecutionMode.CONCURRENT)
class CreatorMatchTest {

    private fun manga(author: String? = null, artist: String? = null) = Manga.create()
        .copy(ogAuthor = author, ogArtist = artist)

    @Test
    fun `a creator matches their own name`() {
        manga(author = "John Smith").matchesCreatorExactly("John Smith") shouldBe true
    }

    @Test
    fun `a longer name that merely starts the same is not a match`() {
        val smith = manga(author = "John Smithson")

        smith.matchesCreatorExactly("John Smith") shouldBe false
    }

    @Test
    fun `a name that merely contains the query is not a match`() {
        val smith = manga(author = "Johnny Smith")

        smith.matchesCreatorExactly("John Smith") shouldBe false
    }

    @Test
    fun `one matching creator among several is enough`() {
        val authors = manga(author = "John Smith, Jane Doe")
        val artists = manga(artist = "John Smith, Another Artist")

        authors.matchesCreatorExactly("John Smith") shouldBe true
        artists.matchesCreatorExactly("John Smith") shouldBe true
    }

    @Test
    fun `the artist field is searched as well as the author field`() {
        manga(author = "Someone Else", artist = "John Smith").matchesCreatorExactly("John Smith") shouldBe true
    }

    @Test
    fun `case and stray whitespace do not decide the match`() {
        val smith = manga(author = "  john   SMITH ")

        smith.matchesCreatorExactly("John Smith") shouldBe true
        smith.matchesCreatorExactly("John   Smith") shouldBe true
    }

    @Test
    fun `semicolons separate creators the same as commas`() {
        val smith = manga(author = "Jane Doe; John Smith")

        smith.matchesCreatorExactly("John Smith") shouldBe true
    }

    @Test
    fun `an entry with no creator listed does not match anything`() {
        val untitled = manga()

        untitled.matchesCreatorExactly("John Smith") shouldBe false
        manga(author = "  ", artist = "").matchesCreatorExactly("John Smith") shouldBe false
    }

    @Test
    fun `the whole query has to be a creator rather than a fragment of the field`() {
        // The field holds two names; neither is the query on its own.
        val pair = manga(author = "John Smithson, Johnny Smith")

        pair.matchesCreatorExactly("John Smith") shouldBe false
    }

    @Test
    fun `a blank query matches everything so an empty search is never filtered away`() {
        manga(author = "Anyone").matchesCreatorExactly("   ") shouldBe true
    }
}
