# Muses voice: fix "not an editable playlist" + better accuracy (two parts, light)

Scope is ONLY what is in `MUSES_VOICE_NOTES.md` (repo root). Everything else (audio capture, beep, first word, setup wizard, end-pointing, background playlist loading, fallback classifier) is LATER: do not start it.
Read: this file, `MUSES_VOICE_NOTES.md`, the last 3 sections of `HANDOFF.md`. Use the zip the owner attached. First run
`ls desktopApp/src/main/kotlin/com/music/bitchord/desktop | grep -i voice` (and the same for `src/test`): file names may differ slightly; use what is really there.
Two accounts, one after the other: X1, X2. Each returns the full updated `Muses.zip`.

## Conditions and rules (short)
Free-tier Claude accounts, Sonnet 5.5 medium, fresh chat each. Nobody can compile; the owner's GitHub build compiles and he tests on his laptop. The owner cannot answer questions: decide, write the decision in HANDOFF.md, continue.
Never open `DesktopApp.kt` whole (8,500+ lines): `grep -n` then `sed -n 'A,Bp'`. Short replies. If the limit is near: stop, write the HANDOFF section with a **Deferred** list, output the zip.
1. No new dependencies or version changes; only APIs already used in the repo; copy exact imports; never guess an API. No change to playback, library or account logic: voice only calls existing actions through the adapter. Must compile on Linux. Do not edit `sharedUi`, `shared`, `app`.
2. Compile-review every file you touch (imports, nullability, `@Composable` context, duplicate names via grep, no `private` type in an `internal` signature). Pure logic gets unit tests; no test needs a microphone, window, model or network.
3. **Privacy unchanged:** no transcript text, playlist/song/artist names or recognised words in logs, settings or files. Log only result KINDS and source codes. Anything new that remembers is in memory only and cleared on app exit or when voice is switched off.
4. Mark edits to existing files `// VOICE FIX <part> hook`. Append `## VOICE FIX <part>` to HANDOFF.md (Did / Files / Decisions / Verified (say plainly NOT compiled) / Deferred / Exact next step). Output the full project as `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`).

## X1: fix the bug and make failures honest (the adapter in `DesktopApp.kt`, `DesktopVoiceController.kt`, the overlay messages, tests)
From the notes: "Open bug" and "Safety / UX".
1. **Reasoned results.** The voice adapter currently answers `Boolean`. Replace it (for add-to-playlist and play-playlist at least) with a small result type: `Done, NotFound, NotEditable(name), Ambiguous(options), NothingPlaying, LibraryNotLoaded, ApiFailed(code)`. The controller maps each to an accurate overlay message ("X isn't one of your editable playlists", "Nothing is playing", "Your library is still loading", ...). Log only the result kind and which source list matched (`local`, `user`, `shelf`) as codes.
2. **Fix `addCurrentToPlaylist`** (grep it in `DesktopApp.kt`). It only looks in `currentVoiceLocalPlaylists()` + `currentVoiceUserPlaylists()` (`InnertubeParser.parseUserPlaylists`, which keeps only the account's own playlists), while `playPlaylist` also uses every shelf item with a `browseId`. Do this:
   - Look in the same candidate set as `playPlaylist`, but only EDIT when the match is an editable list. A match that is playable but not editable returns `NotEditable(name)` and the message says so.
   - If the library is empty or stale (`libraryState` / `libraryStale`), trigger the existing refresh and wait for it (bounded, about 4 s) instead of failing; return `LibraryNotLoaded` if it is still not there.
   - Use the same source as the player's own "add to playlist" button for "the current song" (grep it), so voice and the button always agree; `selectedSong == null` returns `NothingPlaying`.
   - Names with emoji, "&", and spelling differences ("work out" vs "Workout") must not break matching: normalise both sides with the existing helper (X2 will improve it later).
3. **Library edits are safer.** A higher confidence requirement than playback (named constants in one place), never silently pick a weak match, and show "Added <song> to <playlist>" with an Undo that calls the existing remove action if one exists (if none exists, no Undo and say so in HANDOFF).
4. **Tests:** the result-to-message mapping, the "playable but not editable" decision as a pure function over fake candidate lists (local/user/shelf), the library-ready wait with a fake clock.
Acceptance: "add this to my X playlist" works, or names the real reason; no `Boolean` results remain in the voice adapter for these two actions.

## X2: better matching accuracy (new files for the index/matcher/session, changes in the parser, test resources, tests)
From the notes: "Accuracy" 1 to 7 and "Speed" 1 to 2. Skip item 8 (classifier).
1. **Held-out phrases FIRST.** Before reading the parser's regexes, write `desktopApp/src/test/resources/voice/heldout_phrases.tsv` with about 150 realistic phrases (tab separated: phrase, expected action, arguments): every command in several phrasings, filler words, mishearings ("billy eilish", "work out" for "Workout"), numbers ("volume to forty"), self-repair ("play my, um, workout, actually the road trip playlist"), and phrases that must be `Unknown`. Add `DesktopVoiceEvalTest` running the parser against it, reporting pass rate and WRONG-ACTION rate (executing something other than expected is far worse than `Unknown`); assert wrong-action <= 3 percent and print every failing phrase in the message. Fix the code, never the phrase, unless the expectation was wrong.
2. **Library index** (`VoiceLibraryIndex`, in memory): normalised names of playlists, artists and songs (lower case, strip emoji and punctuation, "&" to "and", digits and words, ignore "the/my/playlist/song") with precomputed tokens and a **phonetic key** (a compact Metaphone-style English key in pure Kotlin, tested; not Soundex) plus a small alias table as data ("work out"="workout", "billy"="billie"). Rebuilt only when the library changes (a `version` bumped when the library loads or goes stale), never re-normalised per command. Build it when the library loads, not at command time.
3. **Matcher** (`DesktopVoiceMatcher`): text similarity (token overlap + edit distance) and phonetic similarity, ranked candidates with the **margin** over the runner-up. Execute only when top score >= HIGH and margin >= MARGIN; otherwise return up to 3 candidates for the overlay; below LOW return nothing. Songs and artists use the same rule (playlists already have a margin: reuse it). All thresholds are named constants in one object, stricter for library edits.
4. **Type priors:** explicit words win ("playlist", "song", "track", "album"); "my X" leans playlist; "X by Y" is a track; the destination of add-to-playlist must be a playlist; a bare "play X" that matches both a playlist and a song above LOW returns `Ambiguous` and asks which.
5. **Short-lived context** (`VoiceSession`): in memory, 2 minutes time to live, cleared on app exit and when voice is switched off: last played entity, last candidate list, last playlist mentioned. Enables "the first one", "the second one", "no, the other one", "add it to that one". Never written anywhere.
6. **Self-repair and numbers:** keep the text after "actually", "no", "I mean", "sorry", "wait"; drop "um/uh/like"; "volume to forty/40", "top five", and number words inside playlist names.
7. New `VoiceCommand` cases only where needed (for example `Choose(index)`, `Ambiguous(options)`); keep the old names so the controller still compiles and list every contract change in HANDOFF. Use X1's result type for the outcomes.
Acceptance: the held-out test runs and prints its metrics; the index is built once per library version; nothing in the matching code touches disk.

## Owner's route
1. Put `MUSES_VOICE_FIX_PLAN.md` and `MUSES_VOICE_NOTES.md` in the root of the zip that has the working voice feature. Ignore `MUSES_VOICE_V2_PLAN.md` (the bigger plan; kept for later).
2. Run X1, then X2, passing the returned zip on. Build after X2 (or after X1 too if you want the bug fix tested early).
3. Test: "add this to my <playlist> playlist" on a playlist you own, on one you only saved (expect the honest message), and a few mis-heard names.
