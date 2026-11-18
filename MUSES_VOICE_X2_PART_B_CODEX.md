# VOICE FIX X2, PART B: instructions for ChatGPT Codex

You are finishing half of "X2: better matching accuracy" from `MUSES_VOICE_FIX_PLAN.md`. Part A (pure logic, tests, held-out phrases) is already written and is described below. Part B is the wiring, the parser changes and the controller changes. Read this whole file before touching anything. Work in the order given. Do not skip the verification steps.

## 0. Ground rules (same as the plan, short)
- No new dependencies, no version changes, only APIs already used in the repo (copy exact imports). Must compile on Linux.
- Do NOT edit `sharedUi`, `shared`, `app`, any Gradle file. No change to playback, library or account logic: voice only calls existing actions through the adapter.
- NEVER open `DesktopApp.kt` whole (8,500+ lines). Use `grep -n 'pattern' file` then `sed -n 'A,Bp' file`.
- Privacy: no transcript text, playlist/song/artist names or recognised words in logs, settings or files. Log only result KINDS and source codes (see `VoiceListLog` in `DesktopVoiceLibraryOps.kt`). Everything that remembers (`VoiceSession`, `VoiceIndexHolder`) is memory only and cleared on app exit and when voice is switched off.
- Mark every edit to an EXISTING file with a comment `// VOICE FIX X2 hook`.
- NEVER change a phrase in `desktopApp/src/test/resources/voice/heldout_phrases.tsv` to make a test pass. Fix the code. Change an EXPECTATION only if it was truly wrong, and say why in HANDOFF.md.
- Nobody can compile on the owner's side; his GitHub build compiles. If you CAN run `./gradlew :desktopApp:test --tests '*Voice*'` here, do it and use the output. If you cannot, say so plainly ("NOT compiled") and compile-review by hand: imports, nullability, `@Composable` context, duplicate names (grep), no `private` type in an `internal` signature, `when` exhaustiveness over the sealed types.
- The owner cannot answer questions. Decide, write the decision in HANDOFF.md, continue.
- If you run low on budget: stop, write the HANDOFF section with a **Deferred** list, output the zip.

## 1. What Part A already did (do not rewrite; extend only where stated)
All in `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`, package `com.music.bitchord.desktop`, all `internal`:

| File | Contents |
|---|---|
| `DesktopVoiceText.kt` | `VoiceText.words(text)` (lower case, accents off, `&` becomes "and", emoji/punctuation dropped, apostrophes removed), `VoiceText.numbersToDigits(words)`, `VoiceText.applyAliases(words)`, `VoiceText.tokens(text)` (words + digits + aliases + noise dropped); `VoiceAliasTable.ENTRIES` (data); `VoicePhonetic.key(word)`, `keys(tokens)`, `compactKey(tokens)` (Metaphone-style) |
| `DesktopVoiceIndex.kt` | `VoiceEntityKind {PLAYLIST, ARTIST, SONG, ALBUM}`, `VoiceTerms.of(text)`, `VoiceIndexInput(kind, title, id, artist, source, editable)`, `VoiceIndexEntry`, `VoiceLibraryIndex`, `VoiceIndexBuilder.build(version, inputs)`, `VoiceIndexHolder` with `get(version) { inputs }`, `getFor(inputs)` (content signature, no manual version), `current()` (never builds), `clear()`, `builds` |
| `DesktopVoiceMatcher.kt` | `VoiceMatchThresholds` (all constants), `VoiceMatchMode {PLAY, EDIT}`, `VoiceTypeHint.from(words, hasArtistPart)`, `VoiceMatch`, `VoiceMatchOutcome {Confident(match, margin), Candidates(options), NoMatch}`, `VoiceMatcher.scoreText(a, b)`, `score(termsA, termsB)`, `rank(...)`, `decide(query, index, mode, kinds, hint, artist)`, `decideRanked(...)` |
| `DesktopVoiceSession.kt` | `VoiceRef(kind, label, id)`, `VoiceSession` (2 min TTL, `rememberPlayed/rememberPlaylist/rememberCandidates`, `lastPlayed/lastPlaylist/lastCandidates`, `choose(zeroBased)`, `other()`, `clear()`), `VoiceChoice {Index(zeroBased), Other}`, `VoiceChoicePhrases.parse(words)` and `isLastPlaylistReference(words)`, `VoiceSelfRepair.apply(words)`, `VoiceNumbers.volumePercent(words)` |
| tests | `DesktopVoiceMatchingTest.kt`, `DesktopVoiceSessionTest.kt`, `DesktopVoiceEvalTest.kt`, resource `src/test/resources/voice/heldout_phrases.tsv` (179 phrases) |

Part A is NOT compiled and its tests have NOT been run. Step B0 below is to compile-review it.

`VoiceMatchThresholds` (matcher: songs and artists) is separate from X1's `VoiceConfidence` (playlists: `PLAY_MIN 0.72`, `PLAY_MARGIN 0.05`, `EDIT_MIN 0.85`, `EDIT_MARGIN 0.10`). Leave both objects as they are. Do not merge them.

## 2. Part B tasks, in this order

### B0. Compile-review Part A (15 minutes, no edits unless something is wrong)
Read the 4 new main files and 3 new test files once. Watch-list: `when` branches used as statements with `if` and no `else` (fine, but check), smart casts on `hint.kind` in `VoiceMatcher.rank` (a `val` in a data class of the same module: fine), `VoiceSession.fresh` generic over the private nested `Stamped<T>`, `assertIs<T>()` return values, `sumOf`/`maxOf` overload resolution on `Double`. If you fix something, note it in HANDOFF.

### B1. Run the held-out eval FIRST (baseline)
Before any parser change, run (or hand-trace a sample of) `DesktopVoiceEvalTest` to see the baseline. Write the baseline numbers (or "could not run") in HANDOFF. Expect failures in these groups: self-repair, `SetVolume`, `Choose`, `ChooseOther`, `AddToLastPlaylist` (the commands do not exist yet), and a few `Unknown` phrases that the old parser turns into something else (`turn on the lights`).

### B2. New `VoiceCommand` cases (file `DesktopVoiceCommands.kt`)
Keep EVERY old case and name. Add exactly:
```kotlin
data class SetVolume(val percent: Int) : VoiceCommand        // "volume to forty"
data class Choose(val number: Int) : VoiceCommand            // 1-BASED: "the second one" is Choose(2)
object ChooseOther : VoiceCommand                            // "no, the other one"
object AddToLastPlaylist : VoiceCommand                      // "add it to that one"
```
Do NOT add `Ambiguous` as a `VoiceCommand`: ambiguity is an OUTCOME decided at action time (the parser has no library songs), and X1 already has `VoicePlaylistResult.Ambiguous`. Decision to write in HANDOFF: "plan item 7 listed `Ambiguous(options)` as a command; it is an outcome instead".
Then:
1. `grep -rn 'VoiceCommand\.' desktopApp/src` and update every `when (command)` so it stays exhaustive (the controller's `execute` is one; there may be others, including tests).
2. In `DesktopVoiceEvalTest.describe`, add branches `is VoiceCommand.SetVolume -> "SetVolume" to command.percent.toString()` and `is VoiceCommand.Choose -> "Choose" to command.number.toString()` (the `else` already handles the objects).

### B3. The parser "front door" (`DesktopVoiceCommandParser.parse`, same file)
Insert at the very top of `parse`, before the existing pipeline (mark `// VOICE FIX X2 hook`), in this order:
1. `val words = VoiceSelfRepair.apply(VoiceText.words(text))`; if empty return `Unknown`.
2. `VoiceChoicePhrases.parse(words)` gives `Choose(zeroBased + 1)` for `VoiceChoice.Index`, `ChooseOther` for `VoiceChoice.Other`.
3. `VoiceNumbers.volumePercent(words)` gives `SetVolume(value)`.
4. Continue with the existing logic using `words.joinToString(" ")` as the text. The existing `normalize` must still run (it keeps apostrophes, `VoiceText.words` removes them; the old code strips them again later, so this is fine; verify "don't play that" and "i'm in the mood for ..." still behave).
Then in the `ADD_TO` branch: before matching the playlist name, `if (VoiceChoicePhrases.isLastPlaylistReference(VoiceText.words(rest))) return VoiceCommand.AddToLastPlaylist`.
Known traps (all covered by phrases in the TSV): `no scrubs`, `sorry`, `wait for it`, `like a prayer` and `no shuffle` must survive self-repair (Part A already protects them; if one fails, fix `VoiceSelfRepair`, do not special-case the parser). `the first one hundred songs` must stay `Unknown`.

### B4. Type priors and the `Unknown` leaks in the parser
1. `turn on the lights` (and similar): the old `PLAY_HEAD` accepts `turn on` and `switch to` with any rest. Decision for you to make and record: only accept those two heads when the rest starts with `my `/`the `/`some ` AND contains one of `playlist|mix|mixtape|collection|music|radio`, or when `preferPlaylist`. Everything else becomes `Unknown`.
2. `play my X` leans playlist; `play X by Y` is a track; explicit "playlist/song/track/album" wins. This is already how `parsePlay` works for the first and third; do not duplicate it. The remaining prior (a bare `play X` matching both a playlist and a song) is decided at action time in B6, with `VoiceTypeHint.from(VoiceText.words(spoken), hasArtistPart)`.
3. Run the eval again. Iterate until wrong-action is at most 3 percent. Print and read every `[WRONG]` line first, then `[MISS ]`.

### B5. Controller (`DesktopVoiceController.kt`, `// VOICE FIX X2 hook`)
1. Own one `VoiceSession` inside the controller (constructor default `VoiceSession()`; tests pass a fake clock). Call `session.clear()` where the controller handles "voice switched off" and on dispose/exit (grep `enabled`, `dispose`, `close`, `shutdown` in the controller). 
2. `execute`: add branches (keep it exhaustive):
   - `SetVolume` -> `actions.setVolume(percent / 100f)`; message "Volume N percent". Add `val setVolume: (Float) -> Unit = {}` to `VoiceActions` as the LAST parameter with a default so existing fakes and tests still compile. In `DesktopApp.kt` wire it with grep (`grep -n 'volumeStep' DesktopApp.kt`): use the SAME volume state the `volumeStep` lambda changes, clamped to 0f..1f; invent nothing new.
   - `Choose(number)` -> `session.choose(number - 1)`; if null say "Nothing to choose from"; else act on the `VoiceRef`: for a song ref play it (see below); for a playlist ref call `playlistOutcome(PLAY, ref.label)` (labels with the " (on this computer)" / " (your account)" suffix are the weak spot: see B8 stretch).
   - `ChooseOther` -> `session.other()`; same handling; null says "Nothing to choose from".
   - `AddToLastPlaylist` -> `session.lastPlaylist()`; null says "I don't know which playlist you mean"; otherwise `playlistOutcome(ADD, ref.label)`.
3. Remember things (memory only): after a successful play of a song or playlist call `session.rememberPlayed(VoiceRef(kind, label, id))`; when the controller shows song choices (`DesktopVoiceState.Done.choices`) call `session.rememberCandidates(...)` with the same up-to-3 songs; after any playlist add or play, `session.rememberPlaylist(...)`. For `VoicePlaylistResult.Ambiguous` remember the option labels as `VoiceRef(PLAYLIST, label, id = "")`.
4. Logging stays kinds and codes only. Nothing in the session is ever logged (toString is already redacted; do not interpolate refs into strings that are logged).

### B6. Use the matcher and the index
a) **Playlists (X1's resolver):** in `VoiceListResolver.resolve` (file `DesktopVoiceLibraryOps.kt`), change the default `score` to `{ q, title -> maxOf(DesktopVoiceCommandParser.playlistScore(spokenForm(q), spokenForm(title)), VoiceMatcher.scoreText(q, title)) }`. This adds phonetic and alias matching while keeping every old score as a floor. It will change some numbers in `DesktopVoiceLibraryOpsTest`: re-trace each test that fails by hand; keep the INTENT (never silently pick a weak match; margins). If a test's intent genuinely conflicts, change the test expectation and explain in HANDOFF. Do not lower `VoiceConfidence` values.
b) **Songs and artists (controller `play` and `searchAndPlay`, around lines 514 to 560 of `DesktopVoiceController.kt`; read them with `sed -n`):** the search results are transient, so build a small throwaway index per command: `VoiceIndexBuilder.build(0L, results.map { VoiceIndexInput(VoiceEntityKind.SONG, it.title, it.videoId, artist = it.artist) })`, then `VoiceMatcher.decide(VoiceTerms.of(command.title), index, VoiceMatchMode.PLAY, setOf(VoiceEntityKind.SONG), artist = command.artist?.let { VoiceTerms.of(it) })`. `Confident` plays it; `Candidates` become the overlay choices (up to 3, same as today); `NoMatch` says it could not find it. Keep `confirmBeforePlay` behaviour exactly as it is today. Replace the controller's own scoring only where the matcher replaces it; keep the existing controller tests green (re-trace them).
c) **Library index (the real accuracy gain: "billy eilish" becomes "Billie Eilish"):** add `val libraryIndex: () -> VoiceLibraryIndex = { VoiceLibraryIndex.EMPTY }` as the LAST parameter of `VoiceActions` (default, so tests compile). In `DesktopApp.kt` (grep `voiceListCandidates`, `libraryState`) keep ONE `VoiceIndexHolder` and fill it when the library loads, never at command time: a `LaunchedEffect(libraryState, playlists)` (check how the surrounding code already launches effects; copy its imports) that calls `holder.getFor(inputs)` on `Dispatchers.Default`, where `inputs` = the playlists from `voiceListCandidates()` mapped to `VoiceIndexInput(PLAYLIST, title, id, source = source, editable = editable)`, plus artists/songs ONLY if the library state already holds them (grep the shelf item types; if they are not there, index playlists only and put "artists/songs not indexed" in Deferred). `libraryIndex = { holder.current() }`. Clear the holder (`holder.clear()`) when voice is switched off.
   Then in the controller, for `Play(title, artist)` and `PlayPlaylist/AddToPlaylist`: if the library index has entries, run `VoiceMatcher.decide` on it FIRST with the right kinds (playlist actions: `setOf(PLAYLIST)`; add-to-playlist destination: ALWAYS only PLAYLIST; a song or artist query: SONG and ARTIST). A `Confident` ARTIST/SONG match replaces the spoken text with the library's own title before searching; a `Candidates` result is not acted on silently. A bare `play X` that is both a playlist and a song (use `VoiceTypeHint.from`) must produce the `Candidates` outcome and the overlay asks which; the user's "the first one" then arrives as `Choose(1)`.
d) The index must be built once per library version. Nothing in the matching code touches disk. Verify with `grep -rn 'File\|Files\.\|readText\|writeText' DesktopVoiceText.kt DesktopVoiceIndex.kt DesktopVoiceMatcher.kt DesktopVoiceSession.kt` (must find nothing).

### B7. Tests to add or update
- Controller tests (`DesktopVoiceControllerTest.kt`): `Choose`, `ChooseOther`, `AddToLastPlaylist` with a fake session clock, session cleared when voice is switched off, `SetVolume` calls `setVolume(0.4f)` for "volume to forty".
- Library ops tests: only the re-traced numbers from B6a.
- Keep every existing test passing unless you traced a real conflict (then explain).

### B8. Stretch (only if budget remains; otherwise list under Deferred)
`VoicePlaylistResult.Ambiguous` carries only labels. For "the first one" on a PLAYLIST ambiguity to be exact when two lists share a title, extend it with an optional `ids: List<String> = emptyList()` (default, so old code compiles) and have the adapter fill it; `Choose` then plays by id. Skip if it grows beyond about 40 lines.

### B9. Finish
1. Append `## VOICE FIX X2 PART B` to `HANDOFF.md` with: Did / Files / Decisions / Verified (say plainly NOT compiled and tests NOT run, unless you ran them) / Deferred / Exact next step. Include the eval numbers (baseline and final) and EVERY contract change (new `VoiceCommand` cases, new defaulted `VoiceActions` parameters, resolver default scorer).
2. Output the full project as `Muses.zip` (exclude `.git`, `.gradle`, `.kotlin`, `build`).

## 3. Definition of done
- [ ] `DesktopVoiceEvalTest` prints its metrics; wrong-action at most 3 percent (or the exact remaining failures are listed in HANDOFF under Deferred).
- [ ] Every `when` over `VoiceCommand` is exhaustive; old command names unchanged.
- [ ] `VoiceActions` changes are only new LAST parameters with defaults.
- [ ] No names in logs; session and index cleared when voice is off.
- [ ] No edit outside `desktopApp`; no dependency change; `DesktopApp.kt` edits are small and marked.
- [ ] HANDOFF section written; zip produced.

## 4. Things that will bite you (read twice)
- `"play"` alone is `Resume`, `"play some music"` is `Resume`: do not let the new front door change that.
- `VoiceText.words` removes apostrophes; the old `normalize` keeps them. Compare through the same function on both sides in any new code.
- `VoiceChoicePhrases.parse` treats a lone `one`/`two`/`1` as a choice. That is intentional, because the controller only acts on it when `session.lastCandidates()` is non-empty; with no candidates it must say "Nothing to choose from" and never do anything else.
- Do not call `holder.getFor` or `VoiceTerms.of` over the whole library at command time. Only `holder.current()`.
- `VoiceMatchOutcome.NoMatch` is named that way on purpose (`Nothing` is a Kotlin type).
