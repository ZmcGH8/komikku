# Houri+ (custom build)

Personal build of [Houri](https://github.com/PineappleTwilight/houri) (Apache-2.0, a fork of Komikku /
TachiyomiSY / Mihon) with extra library features. Built by `.github/workflows/houri_plus_build.yml` on every
push to the `houri-plus` branch and published as a release tagged `h<commit count>`, which the in-app
updater picks up. The Houri base is updated by hand when wanted.

`signing.keystore` is the fixed key every release is signed with, so updates install over each other.
If a `CUSTOM_SIGNING_KEY` secret is set (base64 of the same keystore), the workflow uses that instead.
