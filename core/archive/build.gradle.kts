plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.core.archive"
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.libarchive)
    implementation(libs.unifile)
    // KMK -->
    implementation(projects.core.common)
    implementation(libs.injekt)
    // EpubTextSource serves a book in the shape the text-source API expects, and two libraries now
    // serve books (the built-in EPUB library and the manga library) that cannot depend on each
    // other. Shared here so they cannot drift, at the cost of this module seeing the extension API.
    // No cycle: source-api depends on nothing here.
    implementation(projects.sourceApi)
    // KMK <--
}
