# Komikku+ (custom build)

Personal build of Komikku with extra library features. Built by `.github/workflows/custom_build.yml`,
which rebases the `custom` branch onto the official Komikku every day and publishes a release
(`r<commit count>`) that the in-app updater picks up.

`signing.keystore` is the fixed key every release is signed with, so updates install over each other.
If a `CUSTOM_SIGNING_KEY` secret is set (base64 of the same keystore), the workflow uses that instead.
