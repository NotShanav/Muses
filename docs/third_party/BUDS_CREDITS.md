# Earbuds protocol credits (experimental "Earbuds" feature)

Muses' experimental earbuds support (OnePlus / OPPO / realme / DIZO earbuds, off by default) re-implements a
**small subset** of a wire protocol that other people reverse-engineered and documented. No source code was
copied; the Kotlin in `DesktopBudsProtocol.kt`, `DesktopBudsSession.kt` and `DesktopBudsNames.kt` was written
from the documentation. Muses and the projects below are all GPL-3.0 (documentation of one is CC-BY-SA-4.0).

## Primary source

- **QuickBuds** by spizganed, GPL-3.0: https://github.com/spizganed/QuickBuds
  - Read: `docs/PROTOCOL.md` from the QuickBuds **6.1.1** source zip (file dated 2026-11-12 in that zip; the
    document's own header says "Matches QuickBuds 5.4.5 (version code 52)"). The zip carried no `.git`, so
    **no commit hash is known**; the version and the header line above identify what was read.
  - `core/src/wire.rs` of the same zip was read once to confirm the frame length encoding (standard LEB128).
  - Used: transport and UUIDs (section 1), frame format (2), sequence numbers (3), command classes (4),
    init sequence (6), subscriptions (8), pushes (10), requests from the buds (11), battery and wearing (13),
    noise control (14), feature switches (16).

## Sources QuickBuds itself credits

- OppoPodsManager by Zhaoyi-ya: https://github.com/Zhaoyi-ya/OppoPodsManager
- Pods-Protocol-Reverse-Engineering by Star-ZER0 (documentation, CC-BY-SA-4.0): https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering
- OppoPods by Leaf-lsgtky: https://github.com/Leaf-lsgtky/OppoPods
- OPPO-Pods-Win by Zhaoyi-ya (repository name as given in the Muses plan; address not checked)
- DevPods by ORION2809 (repository name as given in the Muses plan; address not checked)

## Trademarks

OnePlus, OPPO, realme, DIZO and HeyMelody are trademarks of their respective owners. They are used here only to
say which devices work. Muses is not affiliated with, endorsed by or sponsored by any of them.

## Privacy

Nothing leaves the computer. Muses talks to the earbuds you paired in Windows, nothing else. Debug logs of
control frames exist only when the debug switch is on (default off) and never contain device names or addresses.
