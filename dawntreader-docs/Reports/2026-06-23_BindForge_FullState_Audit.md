# BindForge Full-State Audit — 2026-06-23

Read-only investigation of the entire BindForge / bind-editor area as it stands today on
`bindforge-gamemode` (`fc0e5956`, current with `origin/V1.1` through commit `7a1a3f28`,
including today's keyboard auto-assign feature). No code was written or modified.

## TL;DR

- **Data model:** Still BUTTON-only. `ReadOnlyBindingSlot`/`ReadOnlyBindingSlots` (and the
  executable `KeyBinding`/`BindingSlots`) only model `Primary`/`Secondary` child elements.
  AXIS (`<Binding>`/`<Inverted>`/`<Deadzone>`) and standalone settings (bare `Value="..."`
  elements) are real, common shapes in actual `.binds` files but are **silently skipped** by the
  parser — not represented, not erred on, just absent from every map it returns. **Unchanged.**
- **Parser/writer pipeline:** Still exactly parse-live → working-copy → apply, as previously
  documented. **Unchanged.**
- **UI:** Used/Missing tables, Apply/Revert, and now (as of today) a "Fix Missing" button plus a
  per-row auto-fix action — both backed by the new `MissingBindingAutoAssigner`. No conflict UI,
  no AXIS/standalone editing UI anywhere in the panel. **Changed** (auto-assign added) but the
  edit surface is otherwise the same dropdown-based keyboard-only editor as before.
- **Conflict detection:** `BindingsMonitor`'s conflict logic, `BindingConflictRules`,
  `BindingConflictManager`, and the `binding_conflicts` table are **byte-for-byte untouched** by
  today's commits — confirmed by diff, not just by description. Still voice/log-only, no UI
  table. **Unchanged.**
- **`DeviceMappings.xml`/`.buttonMap`:** Zero references anywhere in `app/src/main` — confirmed
  still completely unhandled. **Unchanged.**
- **Backup/restore:** Still exactly the 2026-06-21 audit's findings — `BindingsBackupService`
  creates one timestamped, ever-accumulating backup per Apply; there is still no restore UI, no
  listing, no pruning. None of the backup-path files appear in today's diff. **Unchanged.**
- **Live capture:** `DeviceService`'s own class doc now *claims* "StarVizion, BindForge, and
  push-to-talk all consume these events" — but `BindForgeTabPanel`, `AssignKeyboardBindingDialog`,
  and everything in `elite.intel.ai.hands` import neither `DeviceBus` nor `DeviceService`.
  Editing is still 100% `HudComboBox` dropdown selection; no `KeyListener`, no SDL3 capture, no
  controller-press-to-assign anywhere in the bind editor. **Unchanged in practice; the doc
  comment is aspirational/stale.**

---

## 1. Data model / class shapes actually in use today

All binding-slot shapes live in `KeyBindingsParser.java`
(`app/src/main/java/elite/intel/ai/hands/KeyBindingsParser.java`). There is no separate
class file per model — they're nested in the parser.

**Executable model** (`KeyBindingsParser.java:45-61`):
```java
public class KeyBinding {
    public String key;
    public String[] modifiers;
    public boolean hold;
    ...
}

public record BindingSlots(KeyBinding primary, KeyBinding secondary) {}
```

**Read-only/diagnostic model** (`KeyBindingsParser.java:66-106`) — this is what
`MissingBindingAutoAssigner` and the UI tables consume:
```java
public enum BindingSlotType { PRIMARY, SECONDARY }

public record ReadOnlyBindingSlot(
        String device,
        String key,
        String[] modifiers,
        List<BindingModifier> bindingModifiers,
        boolean hold,
        BindingSlotType slotType,
        boolean keyboardUsable,
        boolean editable
) { ... }

public record ReadOnlyBindingSlots(ReadOnlyBindingSlot primary, ReadOnlyBindingSlot secondary) {}
```

`BindingSlotType` (`BindingSlotType.java:6-9`) only has two values, each mapped to an XML element
name (`"Primary"`/`"Secondary"`) — there is no `AXIS` or `SETTING` variant anywhere in the
type hierarchy.

**It's BUTTON-only — confirmed against real game files, not just the parser code.** A `.binds`
file actually contains three structurally different element shapes:

```xml
<!-- BUTTON action: what the model above represents -->
<YawLeftButton>
    <Primary Device="Keyboard" Key="Key_A" />
    <Secondary Device="{NoDevice}" Key="" />
</YawLeftButton>

<!-- AXIS: a completely different shape, no Primary/Secondary at all -->
<YawAxisRaw>
    <Binding Device="SaitekX52" Key="Joy_RZAxis" />
    <Inverted Value="0" />
    <Deadzone Value="0.00000000" />
</YawAxisRaw>

<!-- STANDALONE SETTING: bare leaf element, no children -->
<MouseSensitivity Value="1.00000000" />
```
(quoted from `dawntreader-docs/Actual Game Files/SaitekX52.binds:19-23,39-43` and `:7`)

In `parseReadOnlyBindingSlots()` (`KeyBindingsParser.java:156-203`), the loop does:
```java
NodeList primaryList = element.getElementsByTagName("Primary");
NodeList secondaryList = element.getElementsByTagName("Secondary");
...
if (primaryBinding != null || secondaryBinding != null) {
    bindings.put(actionName, new ReadOnlyBindingSlots(primaryBinding, secondaryBinding));
}
```
For `<YawAxisRaw>` and `<MouseSensitivity>`, neither tag exists as a child, so both bindings stay
`null` and **the element is never added to the returned map at all** — not flagged unsupported,
just absent. Every downstream consumer (the Used/Missing tables, `MissingBindingAutoAssigner`)
only ever sees the subset of `.binds` that happens to be button-shaped.

**Verdict: unchanged.** This is the same scope limitation documented in prior audits
(`BINDINGS_ANALYSIS.md`, `EXISTING_BINDING_MODEL_AUDIT.md`) — today's auto-assign feature was
built squarely on top of this same BUTTON-only model and does not extend it.

## 2. Parser/writer pipeline

Still exactly the pattern documented before: **parse-live → working-copy → apply.**

- **`BindingsLoader.getLatestBindsFile()`** (`BindingsLoader.java:24-51`) resolves the active
  `.binds` file via `StartPreset.*.start`, falling back to most-recently-modified.
- **`BindingsWorkingCopyRepository.loadOrImportFromGame()`** (`BindingsWorkingCopyRepository.java:65-79`)
  imports a byte-for-byte copy into `elite-intel/bindings/` on first use, tracks a SHA-256
  baseline hash, and refreshes the working copy from the game file only when the working copy is
  still "clean" (`refreshFromGameIfClean`, lines 191-206).
- **`KeyBindingsParser.parseReadOnlyBindingSlots()`** parses the working copy into the diagnostic
  model above for the UI; `parseBindingSlots()`/`parseBindings()` narrow that down to the
  keyboard-executable subset used by command dispatch.
- **`BindingsWriter`** (`BindingsWriter.java:51-127`) edits the working copy directly via
  surgical regex text-range replacement — never a DOM transformer — so unrelated bytes are
  preserved. It only ever locates `slotType.xmlElementName()` (`"Primary"`/`"Secondary"`,
  `BindingsWriter.java:325-359`), so it has the identical BUTTON-only scope as the parser.
- **`BindingsApplyService.apply()`** (`BindingsApplyService.java:54-82`) is the only thing that
  touches the game directory: re-validates XML, backs up the current game file
  (`backupGameFile`, line 118), then atomically writes the working copy over it and updates the
  working-copy baseline hash.

**Verdict: unchanged.** No structural change to this pipeline since prior audits; today's
auto-assign commits only added a *new caller* of the same `BindingsWriter` methods
(`assignKeyboardKey`/`assignKeyboardKeyWithModifier`) — they didn't touch the pipeline itself.

## 3. UI state of `BindForgeTabPanel`

Full current feature set, read directly from `BindForgeTabPanel.java` (834 lines):

| Feature | Where | Notes |
|---|---|---|
| Used / Missing tables | `initData()` lines 248-312, `partitionByKeyboardBinding()` lines 671-684 | Split by `autoAssigner.isKeyboardBound(...)` — **today's change**: this now covers *every* keyboard-capable control in the file, not just the EliteIntel-required subset (that narrower count is still published separately for the AI-tab badge, line 296-299) |
| Per-row click-to-assign | `openAssignKeyboardBindingDialog()` lines 753-786 | Opens `AssignKeyboardBindingDialog`, a dropdown-only modal (see §7) |
| **Fix Missing button** (new today) | `buildFooter()` lines 240-245, `fixAllMissing()` lines 356-372 | Confirms via dialog, calls `autoAssigner.planAll(currentSlots)`, applies off-EDT |
| **Per-row auto-fix action** (new today) | 4th table column wired via `BindingsGroupTableFactory`'s `autoFixHandler`; `autoFixSingleBinding()` lines 374-383 | Calls `autoAssigner.planOne(bindingId, ...)` |
| Apply | `performApply()` lines 331-354 | Delegates to `BindingsApplyService.apply()` |
| Revert | `revertFromGame()` lines 510-524 | Deletes the working copy (discards the EI draft only — not a backup restore) |
| Sync status badge | `updateSyncStatus()` lines 314-329 | Draft vs. synced, from `workingCopyRepo.hasUnappliedDraft()` |
| Close-with-draft prompt | `promptCloseWithDraft()` lines 112-146 | Apply / Keep Draft / Discard on app close |
| Bindings directory picker | `selectBindingsDirectory()` lines 526-540 | Now also calls `DataDirectoryValidator.validateAndWarn(...)` (new today, unrelated to auto-assign) |
| **Conflict UI** | — | **None.** No table, badge, or dialog anywhere in this file references `BindingConflictManager` or conflicts. |
| **AXIS/STANDALONE editing UI** | — | **None.** The table renderer (`groupedBindings()`, lines 648-664) only ever emits action/primary/secondary(/autofix) rows from the BUTTON-only model in §1; axis and setting elements never reach this panel. |

**Verdict: changed** — the Fix Missing button and per-row auto-fix are genuinely new today, and
the Used/Missing split's scope was quietly widened to all keyboard-capable controls. Everything
else (Apply/Revert, sync badge, close-prompt, directory picker, the complete absence of
conflict or axis/setting UI) is unchanged from prior audits.

## 4. Conflict detection

Checked whether any of today's `bccac490..7a1a3f28` commits touched conflict-detection files —
**they did not.** The full changed-file list from the fast-forward was:
```
RequestDockingCommand.java, Bindings.java, BindingsMonitor.java (i18n/DataDirectoryValidator
only), MissingBindingAutoAssigner.java (new), SafeKeyboardKeys.java (new),
DataDirectoryValidator.java (new), JournalParser.java, MissingMissionMonitor.java,
AppController.java, BindForgeTabPanel.java, CommonSettingsPanel.java,
BindingsGroupTableFactory.java, i18n properties, 3 new test files
```
`BindingConflictRules.java`, `BindingConflictManager.java`, and `BindingConflictDao.java` are
**absent from that list** — confirmed untouched by direct diff, not inference.

Current logic, unchanged:

- **`BindingsMonitor.checkForConflictsAndPersist()`** (`BindingsMonitor.java:197-261`) inverts the
  current bindings map into `keyCombo → [actionNames]`, flags non-safe overlaps per
  `Bindings.GameCommand`, and diffs against the persisted `binding_conflicts` table — only
  newly-appearing conflicts are returned/announced.
- **`BindingConflictRules`** (`BindingConflictRules.java:12-96`) holds curated dangerous-pair
  descriptions (e.g. `UI_Back`/`ToggleFlightAssist`) and `isSafeOverlap()` — ship/buggy/humanoid
  state and sub-mode overlays (FreeCam, FSS, etc.) are exempted.
- **Persistence:** `binding_conflicts` table (`conflict_key TEXT UNIQUE`, `description TEXT`,
  created in `00051__schema.sql:30-35`), via `BindingConflictManager`/`BindingConflictDao`.
- **Trigger:** same two paths as missing-binding detection — every `.binds` file-change event
  (`BindingsMonitor.java:148`) and once at app start via `KeyBindCheck.check()`.
- **UI:** grepped the entire codebase for `BindingConflictManager`/`binding_conflicts` — only 4
  files reference it at all: `BindingsMonitor.java`, `PACKAGE.md`, `BindingConflictManager.java`,
  `BindingConflictDao.java`. **No UI consumer exists anywhere.** Conflicts are still
  voice/log-announcement-only (`KeyBindCheck.java:39-45`), exactly as previously documented.

**Verdict: unchanged**, confirmed by diff rather than just by re-reading the same files.

## 5. `DeviceMappings.xml` / `.buttonMap` handling

```
grep -ri "DeviceMappings|buttonMap" app/src/main   →   No files found
```

Zero references anywhere in production source. These files exist only as sample/reference data
under `dawntreader-docs/Actual Game Files/DeviceButtonMaps/` (brought over from the old branch
in yesterday's docs migration) — there is no parser, model, or UI for them anywhere in
`app/src/main`.

**Verdict: unchanged** — still completely unhandled.

## 6. Backup/restore

Re-checked `BindingsBackupService.java` (63 lines) and `BindingsApplyService.backupGameFile()`
(`BindingsApplyService.java:118-131`) directly — neither file appears in today's diff at all.

- **`BindingsBackupService.createBackup()`** (`BindingsBackupService.java:44-61`) writes one
  timestamped `<filename>.<yyyyMMdd-HHmmss>[-N].bak` per call into
  `elite-intel/bindings/backups/`, with collision-safe numeric suffixing. No cap, no pruning —
  every Apply adds one more file, forever.
- **`BindingsApplyService.apply()`** calls this once per successful apply (line 73), and that is
  the *only* call site of `createBackup` outside its own test.
- **No restore mechanism exists.** "Revert" (`BindForgeTabPanel.revertFromGame()`) only deletes
  the in-app working-copy draft and reloads from the live game file — it has nothing to do with
  the backup directory. Re-confirmed by grepping the entire `app/src/main` tree for
  `restore`/`Restore` in binding-related files: no hits outside this absence already documented
  in `dawntreader-docs/Reports/2026-06-21_Backup_System_Audit.md`.

**Verdict: unchanged** since the 2026-06-21 backup audit — same gap, same accumulation behavior,
nothing added or removed.

## 7. Live keyboard/controller capture

`DeviceService.java`'s class doc comment (`DeviceService.java:26-34`) now reads:

> "Singleton shared infrastructure: StarVizion, BindForge, and push-to-talk all consume these
> events rather than owning their own SDL3 context."

This is **aspirational, not actual** — checked directly:

```
grep "DeviceBus|DeviceService" app/src/main/java/elite/intel/ai/hands/        → no matches
grep "DeviceBus|DeviceService" app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java → no matches
```

The only `@Subscribe` in `BindForgeTabPanel.java` (lines 91-94) is for `UiBus`'s
`BindingsUpdatedEvent`, unrelated to device capture. The only consumers of `DeviceBus`/
`DeviceService` anywhere in `app/src/main/java` are `AppController.java` (lifecycle
start/stop) and `InputSettingsPanel.java` (a separate settings panel, for custom-command device
button mapping — not the `.binds` editor).

`AssignKeyboardBindingDialog.java` (the only place a user actually assigns a key in BindForge)
is built entirely from `HudComboBox<KeyOption>`/`HudComboBox<ModifierOption>`
(`AssignKeyboardBindingDialog.java:42-43,68-69`) — dropdown selection, populated from
`EliteKeyboardKeys`'s static allow-list. No `KeyListener`, no `MouseListener`, no SDL3 init, no
"press a key to capture it" affordance anywhere in this dialog or `BindForgeTabPanel`.

`KeyCaptureMapper` exists (`elite/intel/util/KeyCaptureMapper.java`) and does layout-independent
scan-code-to-Elite-token mapping, but it has no callers in the bindings/`ai.hands` package at
all — it's infrastructure for a different feature, not wired into BindForge.

**Verdict: unchanged in practice.** Editing remains entirely dropdown-based. The `DeviceService`
doc comment listing BindForge as a consumer does not match the code — worth flagging back to
Krondor as stale/aspirational documentation rather than treating it as a roadmap signal.

---

## Bottom line — buildable now vs. needs more investigation

**Buildable now, with confidence, because the code is real and read:**
- Anything building on the keyboard auto-assign feature itself (`MissingBindingAutoAssigner`,
  `SafeKeyboardKeys`) — it's small, pure, well-tested (11 cases), and fully understood.
- Anything extending the Used/Missing/Fix-Missing UI pattern, since `BindForgeTabPanel`'s full
  current structure is now documented above section-by-section with line numbers.
- A "missing bindings" UI rescoping decision: there are now two live definitions of "missing" in
  the codebase (the broad keyboard-capable-controls one driving these tables, and the narrow
  EliteIntel-dispatch one from `Bindings.GameCommand` driving voice/log announcements) — this is
  a fact, not a guess, confirmed by reading both call sites.

**Still needs investigation before building on top of it:**
- **AXIS/STANDALONE-setting support.** Nothing here would have to be ripped out to add it — the
  current model just silently ignores those shapes — but extending `ReadOnlyBindingSlot`/
  `KeyBindingsParser` to represent them is new work, not a wiring exercise. No existing partial
  implementation to build on.
- **Conflict UI.** The detection/persistence backend is solid and unchanged, but there is
  nothing to extend on the UI side — a conflicts table/dialog would be built from scratch.
- **Backup/restore.** Per the 2026-06-21 audit, still no restore UI and unbounded backup
  accumulation — if BindForge's onboarding flow is meant to offer "restore if auto-assign goes
  wrong," that has to be designed and built, not wired up.
- **`DeviceMappings.xml`/`.buttonMap`.** Zero existing groundwork; would be a from-scratch parser
  and model if ever needed.
- **Live capture.** Despite the `DeviceService` doc comment's claim, BindForge does not consume
  it today. If live key/controller capture is actually wanted for the editor, that integration
  needs to be designed and built — there's no half-finished hook to pick up.
