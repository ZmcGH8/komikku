package eu.kanade.tachiyomi.data.updater

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
@SingleIn(AppScope::class)
class AppUpdateChecker(
    private val peekIntoPreview: Boolean = false,
) {

    private val getApplicationRelease: GetApplicationRelease by lazy { globalAppGraph.getApplicationRelease }

    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
    ): GetApplicationRelease.Result {
        return withIOContext {
            getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                    forceCheck = forceCheck,
                    // KMK --> the no-MTL variant must fetch its own (nomtl-prefixed) APK assets
                    nomtl = BuildConfig.IS_NOMTL,
                    // Houri+ (custom build): its releases are tagged "h<commit count>"
                    tagPrefix = CUSTOM_TAG_PREFIX.takeIf { BuildConfig.UPDATER_REPO.isNotEmpty() },
                    // KMK <--
                ),
            )
        }
    }
}

val GITHUB_REPO: String by lazy { getGithubRepo() }

fun getGithubRepo(peekIntoPreview: Boolean = false): String =
    // KMK --> Houri+ (custom build) updates from its own repository
    if (BuildConfig.UPDATER_REPO.isNotEmpty()) {
        BuildConfig.UPDATER_REPO
    } else if (isPreviewBuildType || peekIntoPreview) {
        // KMK <--
        "PineappleTwilight/komikku-pineapple"
    } else {
        "PineappleTwilight/komikku-pineapple"
    }

val RELEASE_TAG: String by lazy { getReleaseTag() }

// KMK --> Houri+ (custom build)
const val CUSTOM_TAG_PREFIX = "h"
// KMK <--

fun getReleaseTag(peekIntoPreview: Boolean = false): String =
    // KMK -->
    if (BuildConfig.UPDATER_REPO.isNotEmpty()) {
        "$CUSTOM_TAG_PREFIX${BuildConfig.COMMIT_COUNT}"
    } else if (isPreviewBuildType || peekIntoPreview) {
        // KMK <--
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
