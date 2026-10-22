<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Restore → Replace library**: the library ends up exactly like the backup. Entries that aren't in it leave the library (nothing is deleted), merges get exactly the backup's sources, and read chapters and tracking come from the backup.
- **Restore → Send tracking to trackers**: after restoring, MyAnimeList, AniList, Anime-Planet and other logged-in trackers are updated to the backup's status, chapters and scores.

### Improve

- Hiding library titles on sources: author names spelled in another romanization now count as the same person, and a source that lists no author is matched by a distinctive title. Matches made by older, looser rules are checked again.

### Fix

- Restored merges kept only their scan sources and lost the official one.
- Replace library takes each entry's title, details and cover from the backup, so merges made before their sources loaded no longer stay blank.
- Refreshing a merged entry also refreshes its info source, so it shows that source's own title, description and cover.
- WEBTOON covers on merged entries and custom covers load (their image server needs WEBTOON as referer).
- Entries that were never updated (rebuilt merges, entries moved to another source) are always fetched by a library update, whatever the skip settings.
