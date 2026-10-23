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
- **Sort by Average score**: orders the library by the Avg Rating in each entry's description (our own score), like the Avg Readers sort does for readers. It's separate from the tracker-score sort.
- **Restore → Replace library** also replaces the categories: the app's old categories are removed and the backup's category tree (names, parents, order) is restored exactly.
- **Restore → Send tracking to trackers**: after restoring, MyAnimeList, AniList, Anime-Planet and other logged-in trackers are updated to the backup's status, chapters and scores.

### Improve

- The Author, Artist, Studio and Scanlator sorts are gone from the sort menu; they're still there as Group by options.

- Library covers that aren't the usual book shape are shown whole instead of cropped, so their titles aren't cut off.
- Animated covers (GIF and animated WebP, as some scan groups use) play in the library and on the details screen instead of showing only their first frame.

- Hiding library titles on sources: author names spelled in another romanization now count as the same person, and a source that lists no author is matched by a distinctive title. Matches made by older, looser rules are checked again.

### Fix

- Restored merges kept only their scan sources and lost the official one.
- Replace library takes each entry's title, details and cover from the backup, so merges made before their sources loaded no longer stay blank.
- Refreshing a merged entry also refreshes its info source, so it shows that source's own title, description and cover.
- WEBTOON covers on merged entries and custom covers load (their image server needs WEBTOON as referer).
- Entries that were never updated (rebuilt merges, entries moved to another source) are always fetched by a library update, details and cover included, whatever the skip settings.
