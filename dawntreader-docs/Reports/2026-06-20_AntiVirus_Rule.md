# AV/SmartScreen + No-Code-Signing-Budget Rule — Documentation Check

## Question

Is the rule "EliteIntel must never risk triggering an antivirus/SmartScreen flag, and no money
will be spent on code-signing certificates" already documented anywhere in the repo?

## Answer: No — it did not exist anywhere, in any wording, before this change.

Searched `README.md`, `DEVELOPERS.md`, `TECHNICAL.md`, and `CLAUDE.md` two ways: keyword grep
(`antivirus`, `SmartScreen`, `code sign`/`signing`, `flagged`, `AV`) case-insensitively across
all four files, and a direct read of every security/constraints-flavored section in each file.
Both came up empty for this specific rule.

### What keyword search found

Zero matches in `README.md`, `DEVELOPERS.md`, `TECHNICAL.md`, or `CLAUDE.md` for any of
`antivirus`, `SmartScreen`, `signing`/`code sign`, `flagged`, or standalone `AV`.

A repo-wide grep (not limited to the four target files) did turn up "antivirus" in a few other
places, but none of them state this rule — they're a narrower, unrelated incident:

- `app/src/main/java/elite/intel/devices/PACKAGE.md` (lines 50-56) and the equivalent comment in
  `DeviceService.java` (`configureLwjglNativePath()`) — both describe antivirus holding an
  exclusive lock on LWJGL3's native-library temp files during extraction, and the workaround
  (extracting to a fixed app-owned directory instead of `%TEMP%`). This is about a Windows file-
  locking race during startup, not about a general "don't risk an AV/SmartScreen flag, no signing
  budget" architectural rule.
- Several files under `dawntreader-docs/Reports/` mention "antivirus" only in that same temp-
  extraction context (`KEYBOARD_DEBUG.md`, `BindForge_Punch_List.md`, sync reports, etc.) — none
  state the rule either.

### What the security/constraints sections actually say (read directly, not just grepped)

- **`DEVELOPERS.md` "Coding Standards" / pull-request rejection list** (lines ~95-104) — the
  closest existing thing. Three explicit `⚠ ... will be rejected` rules: no external
  dependencies users must set up themselves; no JNI to an unsigned library unless available on
  both Linux and Windows; no JNI that reads/modifies in-game memory. **None of these mention
  AV/SmartScreen flags as the underlying concern, and none mention code-signing budget at all** —
  the "unsigned library" rule is about JNI binary trust generically, not about avoiding AV
  detection or ruling out signing as a fix.
- **`DEVELOPERS.md` "Security" section** (lines 190-194) — covers voice-input sanitization, API
  key storage, and "no internet-required features beyond Data/TTS/LLM APIs." Nothing about AV,
  SmartScreen, or signing.
- **`TECHNICAL.md` "Build and Distribution"** (lines 157-167) — covers packaging format
  (Shadow fat JAR), installers (NSIS on Windows, bash on Linux), and bundled vs. separately-
  installed native components. No mention of code signing, SmartScreen, or AV risk anywhere in
  this section or the rest of the file.
- **`CLAUDE.md` "Architectural constraints"** — has "No JNI to unsigned libraries, and any JNI
  must work on both Linux and Windows" (mirrors the `DEVELOPERS.md` JNI rule) and "No game memory
  reading, no overlay injection, no game-client modification" for ToS-compliance reasons. Again,
  JNI-trust and ToS-compliance are the stated rationale, not AV/SmartScreen avoidance, and there
  is no mention of code-signing economics anywhere in the file.
- **`README.md`** — no constraints/security section of this kind at all; it's user-facing
  overview/provider documentation.

So the underlying *spirit* — be cautious about unsigned native binaries — already existed
narrowly (JNI-specific), but the broader rule the user described (governing *any* mechanism that
risks an AV/SmartScreen flag, e.g. global input hooks or raw-input registration, not just JNI;
and explicitly ruling out paid code-signing as a mitigation) was not written down anywhere.

## Action taken

Added the rule to `DEVELOPERS.md`, in the same `⚠ ... will be rejected` / "NO EXCEPTIONS" style
as the existing JNI rules, immediately after them:

```diff
 ⚠ **Any pull requests that contain JNI to a library that reads / modifies in-game memory will be rejected.**

+⚠ **Any feature that risks triggering an antivirus or Windows SmartScreen flag (e.g. global
+low-level input hooks, raw-input registration, process injection of any kind) will be rejected.**
+There is no code-signing certificate and no budget to buy one — signing is not an available
+mitigation for this risk. A design that is only acceptable *if* the binary gets signed is not
+acceptable; it must be safe to ship unsigned.
+
 NO EXCEPTIONS
```

Not yet committed — `DEVELOPERS.md` is tracked, diff shown above for review first, per
instructions. `CLAUDE.md` was intentionally left untouched (out of scope; the user asked for the
addition in `DEVELOPERS.md` specifically).
