package tachiyomi.core.common.util.lang

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

private val collator by lazy {
    val locale = Locale.getDefault()
    Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }
}

fun String.compareToWithCollator(other: String): Int {
    return collator.compare(this, other)
}

/**
 * Same ordering as [compareToWithCollator], but built once and compared cheaply: one collator
 * pass per string, after which [CollationKey.compareTo] is a straight byte array comparison
 * with the identical result.
 *
 * Made for sorts that compare the same strings over and over - the library re-sorts every
 * entry with an alphabetical tie-break on every emission, so paying the collator once per
 * entry instead of once per comparison is the difference between O(n) and O(n log n) collator
 * passes per sort.
 */
fun String.toCollationKey(): CollationKey = collator.getCollationKey(this)
