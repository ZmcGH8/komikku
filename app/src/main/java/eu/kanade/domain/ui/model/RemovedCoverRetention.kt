package eu.kanade.domain.ui.model

/**
 * How long a cover stays on disk after its manga leaves the library.
 *
 * Deleting on removal means re-adding a title re-downloads a cover that was already on disk, which
 * is the common browse-then-add cycle. Retaining costs disk, so the window is the user's call -
 * including [IMMEDIATE], which is the old behaviour of dropping the file at once.
 */
enum class RemovedCoverRetention(val days: Int) {
    IMMEDIATE(0),
    ONE_DAY(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    ;

    /** Retention window in milliseconds; 0 means "drop as soon as the library is next opened". */
    val retentionMillis: Long get() = days * 24L * 60L * 60L * 1000L
}
