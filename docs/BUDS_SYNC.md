# Keeping the earbuds feature in step with QuickBuds

Muses' experimental earbuds feature (Settings > Advanced > Experimental > "Earbuds") speaks the
OnePlus / OPPO / realme / DIZO earbud protocol. Muses does not copy QuickBuds. It re-implements a small
subset from QuickBuds' written protocol document and follows upstream only through that document.
Credits and licence: [third_party/BUDS_CREDITS.md](third_party/BUDS_CREDITS.md).

## What this was written from

- QuickBuds **6.1.1** source zip, `docs/PROTOCOL.md` dated **2026-11-12** (its own header says
  "Matches QuickBuds 5.4.5, version code 52").
- No git commit is known (the zip had no `.git`). When you re-read upstream, record the real commit hash
  and date here, and in `BUDS_CREDITS.md`, so the next check has a fixed starting point.

## Which Muses file holds what

| Upstream topic | Muses |
| --- | --- |
| Frame, length encoding, sequence numbers, command numbers, feature ids, service UUIDs | `DesktopBudsProtocol.kt` (`BudsIds`, frame builder and parser) |
| Init order and delays, pushes, polling, read-back after a write | `DesktopBudsSession.kt` |
| Which earbuds count as compatible (names) | `DesktopBudsNames.kt` |
| Windows Bluetooth transport | `DesktopBudsWindows.kt` |
| When to connect, retry back-off, messages | `DesktopBudsController.kt` |

Tests that pin the facts: `DesktopBudsProtocolTest`, `DesktopBudsSessionTest`, `DesktopBudsNamesTest`,
`DesktopBudsWindowsTest`, `DesktopBudsControllerTest`.

## Checklist: re-reading upstream

Open upstream `docs/PROTOCOL.md` (section numbers are those of the 6.1.1 copy; if they moved, search by title).

1. Section 1 Transport: service UUIDs and their order, one long-lived socket.
2. Section 2 Frame format and section 3 Sequence and replies: header bytes, length encoding (LEB128),
   payload offset, `0xFF` for pushes, reply bit.
3. Section 6 Connection and init sequence: command order and delays.
4. Section 8 Subscriptions and section 10 Pushes: register payload (count first) and event ids.
5. Section 11 Requests from the buds: which ones must be answered.
6. Section 13 Battery and wearing: pair layout, charging bit, status values, the "not known" values.
7. Section 14 ANC: set encodings and notify encodings are different; the mask table.
8. Section 16 Feature switches: feature ids, the id used for low latency, per-model oddities.
9. Section 21 Per-model rules: anything that applies to one family only.
10. Skim the rest only for new commands that Muses already sends (a changed number is a bug here).

Then:

1. For each difference, write or change the **test first** (the frame bytes or the decode table), see it
   fail, then change the constant or parser in `DesktopBudsProtocol.kt` / `DesktopBudsSession.kt`.
2. Update the comment on the constant with the new upstream section.
3. Record the upstream version, date and (if known) commit hash in this file and in `BUDS_CREDITS.md`.
4. Add a line to `HANDOFF.md` under a new `## BUDS` section.

## What to ignore

QuickBuds' own app (Android UI, tray, desktop UI) and its release notes do not matter to Muses. Only the
wire format document does. Do not port features Muses does not show (equaliser, gestures, fit test, ...).

## Adding a model-specific quirk

A quirk is a **data entry**, not a new code path. Keep it as a row in a table keyed by the product id
(`BudsState.productId`, the vendor-list id as hex) or by the name pattern in `DesktopBudsNames.kt`:

1. Write the failing test first: the exact bytes the model reports or needs.
2. Add the row (for example "this product id also offers Adaptive", or "this model decodes ANC with the
   realme write bits"). The session and decoders read the table; they do not branch on model names.
3. Mention the source (an upstream section or your own capture) in a comment next to the row.

Known open items of this kind are listed in `HANDOFF.md` (BUDS B1 "Deferred": other brands' ANC encodings,
Adaptive discovery, low-latency quirk on Buds Pro 3 style firmware).
