# BindForge — Running Punch List

**Status:** Planning consolidation. Not a spec — a tracking list of decided items and open items to work through before/during data model design.

---

## A. Settled — `.binds` Schema (fully evidence-backed)

**Moved 2026-06-23 to its own dedicated document:** `BindForge_Binding_Rules_Reference.md` is
now the single source of truth for everything about how Frontier's `.binds` file format and the
game's own binding behavior actually work — the three element types, every slot-level property
and its quirks (including the modifier mislabeling bug and the subset key-set suppression
bug), file location/lifecycle facts, and where the in-game UI itself is incomplete or wrong.
This section is kept only as a pointer so nothing gets maintained in two places at once — see
that document for the actual content.

## B. Settled — Existing Codebase Assessment

- `KeyBindingsParser` / writer / working-copy / apply pipeline: production-quality for **BUTTON only**. AXIS (72 elements) and STANDALONE SETTING (117 elements) are completely unhandled — not buggy, never written. Verdict: **extend, not replace**.
- Clarification: "production-quality for BUTTON" describes the conflict-aware dropdown/parse/apply-back-to-file flow (`AssignKeyboardBindingDialog` + `KeyboardKeyAvailabilityService`), **not** live keypress capture. There is no `KeyListener`/`KeyAdapter` anywhere in the codebase — the user picks an unoccupied key name from a dropdown, they never press a key that gets detected. `KeyCaptureMapper` (built by Krondor — converts a real keypress into the correct layout-independent Elite key token) exists but has zero callers anywhere; it's unused scaffolding, not wired into anything live.
- No database table stores parsed `.binds` content today. Pattern is parse-live → in-memory model → working-copy (with SHA-256 baseline hash, hardened in `b4225482`) → apply-back-to-file.
- `b4225482` added conflict-aware apply safety (detects concurrent game-client edits via baseline hash) — independent of and complementary to the `binding_conflicts` same-key detection system (Section C).
- `BindForgeTabPanel` (renamed from `BindingsTabPanel` in `a8b4b73e`) is now a top-level tab. Purely structural promotion — no new binding capability added. Still keyboard-only editor with Used/Missing tables.
- Decision: AXIS/STANDALONE extension should follow the **same non-database, parse+working-copy pattern** as BUTTON, for consistency — not a new persisted table for binding *content*.
- DeviceMappings/buttonMap editing should follow the same general pattern (in-memory model, transparent to user) but with a **fan-out write step** across N installation locations, since BUTTON-style single-location apply doesn't fit their multiplicity.
- **Architectural constraint, confirmed directly by Krondor 2026-06-21: singletons over dependency injection, everywhere — not just in BindForge.** His stated reasoning: DI "spaghettifies too easily" in this codebase. Any new BindForge service/class (capture services, detection services, fill-logic services, etc.) should be built as a self-registering singleton (the existing `EventBusManager.register(this)`-in-constructor pattern already used elsewhere in the codebase), not constructor-injected.

## C. Settled — Conflict Detection Assessment

- Real logic exists (`BindingsMonitor.checkForConflictsAndPersist()` + `binding_conflicts` table), not naive same-key matching. Genuinely modifier-aware; correctly suppresses cross-vehicle-state (Ship/SRV/On Foot) false positives in most cases.
- **Most significant gap (FN-1):** only compares each action's *Primary* slot (Primary wins over Secondary in `parseBindings()`). A real collision between one action's Secondary and another's Primary is never detected, even though both fire in-game.
- `Hold` vs. tap is not factored into combo identity → false positives (FP-1) for legitimately distinct held/tapped bindings on the same key.
- `ToggleOn` semantics (fire-on-release vs. fire-on-press) ignored entirely → can't reason about same-key ToggleOn/normal pairs (FN-4).
- HOTAS-as-modifier slots excluded from conflict map entirely (`keyboardUsable` filter) → real conflicts involving e.g. `AutoBreakBuggyButton`-style joystick modifiers invisible (FN-6).
- Vehicle-state suppression (`isSubStateModeAction()`) over-suppresses some cases — e.g. `ExplorationFSSEnter` vs. `DeployHardpointToggle` sharing a key is wrongly deemed always-safe (FP-3).
- No dedicated conflict UI exists at all today — surfaces only as a spoken count + log entry. No row highlighting, no inspect/fix-from-here flow.
- Verdict: **extend, not replace** — DB schema + live-diff approach are sound; detection algorithm needs upgrading to operate over full slot sets (Primary + Secondary both sides), fold in `Hold`, narrow the suppression list. UI surface is greenfield.

## D. Settled — `.binds` File Discovery & Lifecycle (informed by cross-referencing EDDiscovery/EliteChroma community tools)

- **The `.#.0` version suffix on a `.binds`/`StartPreset.#.start` filename is assigned by the
  game itself, tied to the game's own version** (e.g. Odyssey's on-foot component bump produced
  `.4.0.`) — **BindForge never mints this number**. It only ever reacts to whatever suffix the
  game already wrote; there is no "create a new preset at version X" code path that originates a
  version number independently.
- **Multiple versioned `.binds` files for the same preset can coexist** in the user's Bindings
  folder (e.g. `DawnTreader.1.0.binds` and `DawnTreader.2.0.binds` side by side, left over from
  before/after a game update). The candidate file is selected by combining both signals — highest
  `.#.0` version suffix **and** most recent modification date — and **the higher version number
  wins by default** when the two signals agree or only weakly disagree. If the two signals
  genuinely conflict (e.g. the higher-version file is stale-dated while a lower-version file was
  touched more recently by something else), **stop and ask the user**, with the UI nudging them
  toward the higher-version candidate as the recommended choice rather than presenting a neutral
  pick. (Community tools split on this: EDDiscovery's `BindingsFile.cs` uses mtime only,
  EliteChroma's `EliteFiles` library uses version-number only; neither alone is trusted here.)
- **First-run / never-customized case:** if the user has never created a custom `.binds` file
  (the file `StartPreset.4.start` points to doesn't exist yet, or the Bindings folder has no
  matching custom file), BindForge must read the game's stock/default preset (resolved via the
  install folder's `ControlSchemes` directory — see open item E.1's sibling problem for
  `DeviceMappings`/`buttonMap`, since `.binds` resolution turns out to have the same two-location
  shape), copy it into the user's actual binds folder, prompt the user to name the new custom
  file, and write `StartPreset.4.start` so the game recognizes it as the active preset. This is
  an onboarding flow, not a silent background file copy.
- **Mandatory first-load backup, non-negotiable:** the very first time Elite Intel runs, before
  BindForge touches anything, it must copy **every** `.binds` file, every `StartPreset.*.start`
  file, and every `.buttonMap` file found in the user's bindings location(s) — regardless of how
  many or how old — into a backup folder inside Elite Intel's own data folder. This applies even
  if there are dozens of stale files from years of game updates; none are skipped or judged
  irrelevant. User data safety takes priority over tidiness here — this backup step is separate
  from (and a prerequisite to) the "ask the user about cleaning up old versions" prompt above.
- **Flagged 2026-06-21, needs reassessment, not just trust:** a backup mechanism already exists in
  the live codebase today, but the user has direct knowledge that it's "ugly" and has caused real
  problems for some users. The *requirement* above (always back up before touching anything) is
  not in question — what's in question is whether the *existing implementation* of that
  requirement is sound enough to build BindForge's onboarding fill-and-restore feature on top of,
  or whether it needs to be reworked first. See the CCTIJ research prompt logged below (Section F)
  for the investigation into what exists today and what's actually gone wrong with it.

## E. Open — Not Yet Decided

1. **Known-paths inventory settled 2026-06-20 (user-verified real paths, corrected); detection mechanism itself still open.** A Java-native installation-discovery mechanism still needs to be designed from scratch — no Java-side code or class design exists yet. An older document (`INSTALLATION_DISCOVERY_RESEARCH.md`) speculated, based on unverified forum testimony, that all storefronts share one `ControlSchemes` location under `%LOCALAPPDATA%\Frontier_Developments\Products\`. **That theory is wrong and has been marked superseded in that document.** Each storefront genuinely has its own separate `ControlSchemes` tree, confirmed against the user's own real machine:

   | Store | Game install folder |
   |---|---|
   | Frontier Direct (standard) | `C:\Program Files (x86)\Frontier\Products\elite-dangerous-64\` |
   | Frontier Direct (alternative) | `%LOCALAPPDATA%\Frontier_Developments\Products\elite-dangerous-64\` |
   | Epic Games (default library) | `C:\Program Files\Epic Games\EliteDangerous\Products\elite-dangerous-64\` |
   | Epic Games (custom library) | varies by drive — Epic supports installing to a user-chosen library location, not just the default; requires enumerating Epic's library config rather than assuming the default path |
   | Steam (default library) | `C:\Program Files (x86)\Steam\steamapps\common\Elite Dangerous\Products\elite-dangerous-64\` |
   | Steam (custom library) | varies by drive — requires Steam library-folder enumeration (`libraryfolders.vdf`), not a fixed path |
   | Oculus Home | `C:\Program Files\Oculus\Software\Software\frontier-developments-plc-elite-dangerous\` |

   Within each storefront's own install folder, `DeviceMappings.xml` lives at `[GameInstall]\Products\<product-name>\ControlSchemes\DeviceMappings.xml` and `.buttonMap` files at `...\ControlSchemes\DeviceButtonMaps\` — confirmed real, separate copies per storefront, each rooted under that storefront's own install location from the table above (e.g. `[Epic install]\Products\elite-dangerous-64\ControlSchemes\DeviceMappings.xml`, `[Steam install]\Products\elite-dangerous-odyssey-64\ControlSchemes\DeviceMappings.xml`).

   This is exactly why the multi-location fan-out save/backup logic (Section D, Section B) was the right call — there really are independent copies to keep in sync per storefront, not one shared file. **Corrected 2026-06-21: a game update CAN overwrite/erase `DeviceMappings.xml`/`.buttonMap` (and the `.binds` files too), but this is not guaranteed or universal — it doesn't happen on every update, and not for every user.** The practical implication is unchanged regardless of frequency: BindForge must always validate these files are present and in the correct locations after a game update, ready to restore from its own backup if they're gone — but the trigger condition is "validate after any update, just in case," not "assume erasure happened." The game install folder is still the only valid location for `DeviceMappings.xml`/`.buttonMap` — they do not work if placed in the user config bindings folder.

   The `.binds`/`StartPreset.4.start` files live in the single stable user-config location (`%LOCALAPPDATA%\Frontier Developments\Elite Dangerous\Options\Bindings\`) shared across every storefront, confirmed already in Section A — but per the correction above, they are not necessarily exempt from being overwritten by a game update either, so the same "always validate, don't assume" posture applies to them too.

   **Still actually open:** the Java code that walks this path list, validates which ones are real installs (vs. stale leftover folders), enumerates Steam library folders for non-default drives (e.g. via `libraryfolders.vdf` — see `INSTALLATION_DISCOVERY_RESEARCH.md` §2 for the parseable format and Elite Dangerous's Steam AppID `359320`), and falls back to a manual folder-picker when nothing is found. None of that has been designed yet — this entry only fixes what paths to check, not how the checking happens.
2. **RESOLVED:** two separate structures (`devices: List<DeviceEntry>`, `buttonLabels`), cross-referenced only by device friendly name — confirmed against real `DeviceMappings.xml`/`.buttonMap` samples (2026-06-20). The two file types are only related by filename/tag-name convention; there's no structural link between them in the source data itself, so merging them into one combined concept would mean inventing a relationship that doesn't exist. Each still gets its own multi-location fan-out save.
3. **RESOLVED:** Krondor and Gnevko already know BindForge work is coming — Gnevko's recent `BindForgeTabPanel` promotion to a full tab was explicitly done in preparation for it, as part of his broader UI update. Krondor expects a pull request before any of this becomes part of a release; this is the existing, already-agreed gate, not a new coordination step to schedule.
4. **RESOLVED (capture mechanism, including a hard constraint correction); scope narrowed 2026-06-21 per Krondor meeting.** live press-it-and-detect capture (vs. BUTTON's dropdown-pick pattern) is needed for AXIS at minimum, since axis positions can't sensibly be picked from a list. Joystick/HOTAS axis+button capture is solved: SDL3 is already a dependency (`org.lwjgl:lwjgl-sdl`), already polling joystick/gamepad state live in `DeviceService` — unaffected by anything below, since device-state polling carries none of the risk discussed next.
   - **Keyboard capture — a platform-native low-level keyboard hook (`SetWindowsHookEx WH_KEYBOARD_LL` / X11 global key-grab) is ruled out entirely, not just deprioritized.** Hard constraint set by Krondor: zero tolerance for anything that risks an antivirus/SmartScreen flag, and no money will be spent on code-signing certificates (which is the main mitigation that reduces — not eliminates — that risk for exactly this class of API, since it's the same mechanism every keylogger uses). Since the risk can't be paid down and can't be tolerated, the mechanism itself is off the table regardless of scoping, library choice, or how narrowly it'd be activated.
   - **RESOLVED: unify under a single SDL3 capture window** — fix the root cause `KEYBOARD_DEBUG.md` identified (`SDL_Init()` missing `SDL_INIT_VIDEO`) by creating a real SDL3 window and using it as the capture surface for keyboard too, alongside the already-working SDL3 joystick/axis polling. Decided by the actual capture requirement, confirmed 2026-06-20: the slot type being filled (button vs. axis) IS known in advance from the data model (`ButtonElement` slots expect button-type input, `AxisElement`'s slot expects axis movement), but **which device produces it is not** — during BUTTON-slot capture, a keyboard key or a joystick/HOTAS button are equally valid, so both must be listened to simultaneously, racing to see which fires first; during AXIS-slot capture, only axis-capable devices are listened to at all (keyboard is irrelevant and ignored). That simultaneous-listening requirement during button capture is a real race between two input sources, not a hypothetical one — running it through one unified SDL3 event loop avoids the cross-thread coordination problem a separate Swing `KeyListener` (AWT event-dispatch thread) racing against SDL3's native polling thread would otherwise create. Both this and the previously-considered Swing-only approach are equally AV-safe (focus-scoped, not a global hook) — the deciding factor was the concurrency requirement, not safety.
   - **Scope for the first iteration, confirmed 2026-06-21: keyboard only.** Controller/HOTAS *capture-editing* (as opposed to the already-working joystick/gamepad *state polling* `DeviceService` does today) is explicitly deferred to a later iteration — the simultaneous keyboard+controller race described above is real but not a v1 requirement. **This does NOT mean falling back to a simpler non-SDL3 mechanism for v1** — the SDL3 capture-window approach is still the target even for keyboard-only capture, specifically so the same mechanism extends cleanly to controllers later without a rewrite.
   - **`KeyCaptureMapper` (`app/src/main/java/elite/intel/util/KeyCaptureMapper.java`) is being repurposed, not just reused.** Originally scaffolding that converts a real keypress into a layout-independent Elite key token, with zero callers. The plan now is to rework it into the actual SDL3 keyboard-capture entry point for BindForge's editor — and to later expand it to cover controller capture too, once that's back in scope. Concrete shape of this rework is undrafted; see the CCTIJ research prompt logged below for what needs investigating first (the `elite.intel.devices` package added for push-to-talk may already contain a working analog).
   - A prior SDL3-based keyboard-polling experiment (`SdlInputService.pollKeyboard()`, StarVizion prototype) was built, correctly diagnosed as broken in `KEYBOARD_DEBUG.md` (2026-06-18 — `SDL_Init()` was called without `SDL_INIT_VIDEO`) then deleted wholesale (`34672d1a`) rather than fixed. **Open question, raised 2026-06-21:** StarVizion (originally meant to become its own overlay-focused section of EliteIntel eventually) has become intertwined with other systems, including possibly push-to-talk — before any new SDL3-keyboard work touches this area, CCTIJ needs to determine whether StarVizion can be cleanly extricated/removed without breaking anything currently depending on it. See the CCTIJ research prompt below.
5. **PARTIALLY RESOLVED — corrected 2026-06-20:** the data model class shapes (`BindingProfile`, the `BindingSlot` hierarchy, the `BindingElement` hierarchy, `DeviceEntry`/`DeviceIdentifier`, the `buttonLabels` map) are drafted in `BindForge_Data_Model.md`. `BindingModifier` confirmed as the existing codebase class (`app/src/main/java/elite/intel/ai/hands/BindingModifier.java`), reused as-is. **Still genuinely open, not implementation detail to defer:** the actual extended-parser/writer method design for AXIS/STANDALONE/DeviceMappings/buttonMap. Previously and wrongly marked as safe to leave to implementation — this is actually something that needs real planning attention before CCTIJ can build it, the same way the data shapes did.
6. **RESOLVED:** raw XML element name (`BindingElement.name`) is sufficient — no synthetic ID needed. Purpose Mode (a planned UI-only re-grouping of existing binds by purpose — e.g. "Left motion" spanning Ship/SRV/On Foot — built only after Game Mode is working) doesn't change or add to the underlying data, it just groups by name; the existing name already serves as the reference key it needs.

## F. BUILT — Onboarding: Missing Key Bind Detection & Auto-Fill (shipped by Krondor, 2026-06-23, ticket KAN-65)

This section was written as forward planning starting 2026-06-21. **As of 2026-06-23, Krondor
built and shipped the actual feature on `origin/V1.1` (commits `3034a06a`/`7a1a3f28`, "Add
one-shot auto-assign for unbound key bindings") before our planning got there.** What follows is
the real implementation, confirmed by reading the merged code
(`dawntreader-docs/Reports/2026-06-23_autofill_sync_report.md` has the full audit) — superseding
the speculative items below it. Kept for the historical record of what was planned versus what
shipped, since they differ in a few real ways.

1. **Detection — confirmed built on existing methods, exactly as Krondor said it would be.**
   `findMissingGameBindings()`/`checkForMissingBindingsAndPersist()` in `BindingsMonitor` were
   real and already shipped (confirmed 2026-06-23, `dawntreader-docs/Reports/2026-06-23_Missing_Bindings_Audit.md`)
   — but the new auto-assign feature **does not use them**. It introduces its own, broader
   definition instead — see item 2.
2. **Two notions of "missing" now coexist, by design — this differs from the original framing
   above.** The new `MissingBindingAutoAssigner.isKeyboardBound(...)` is the single shared
   predicate behind both the Used/Missing tab split *and* the auto-assigner: a control counts as
   bound if either its Primary or Secondary slot is keyboard-usable, checked against **every
   keyboard-capable control in the file** — not just the ones EliteIntel itself depends on for
   command dispatch. Rationale (confirmed in the shipped UI wiring): custom commands can target
   any control, not just EliteIntel's built-in ~80. The original, narrower
   `Bindings.GameCommand`-scoped definition (Section F's original premise) still exists,
   unchanged, but now only drives the voice/log announcements and the AI tab's badge count
   (`BindingsSummaryChangedEvent`) — it no longer drives the bindings tab's own tables.
3. **Fill paths — built differently than planned, same net effect.** No upfront "auto-fill vs.
   manual" choice dialog exists. Instead: a footer **"Fix Missing"** button (enabled only when
   the Missing tab is non-empty) batch-fixes everything after a confirmation dialog, and a new
   per-row auto-fix column fixes one control at a time. Manual assignment remains available
   separately through the existing editor (dropdown-based today, per Section E.4's still-pending
   SDL3-capture work) — so a user effectively still has both paths, just as two independent
   actions rather than a single branching dialog.
   - **Add, never replace, enforced in code:** an edit is only produced for an empty
     (`{NoDevice}`) slot — controller/HOTAS and existing keyboard assignments are never touched.
   - **Never collide, enforced in code:** a chord (key + optional modifier) is checked against
     every chord already in the file and against everything already planned in the same batch
     before being used.
   - Skips are categorized and reported: `BOTH_SLOTS_OCCUPIED`, `NO_EDITABLE_SLOT`,
     `NO_FREE_KEY` — surfaced to the user in a summary dialog after applying, broken down by
     reason.
4. **Auto-fill key/modifier pool — broader than what was speculated below.** `SafeKeyboardKeys`
   includes the cross-layout-safe letters as planned (`E R T U I O P S D F G H J K L B N`,
   excluding `Q W A Z M Y`), **plus digits `0`–`9`, the full numpad, and `F1`–`F12`** — none of
   which were in the original speculative list. Safe modifiers are `LeftControl`/`LeftShift`/
   `LeftAlt`/`RightShift` (matches what was planned), explicitly excluding `RightAlt` since it's
   AltGr on AZERTY/QWERTZ (a detail the original planning didn't call out). Allocation order:
   every base key × every safe modifier first, then plain unmodified keys — confirms the
   "don't steal desirable bare keys" principle, now enforced in code via `orderedChords()`.
5. **Runs off the EDT, as the original UI-implementation item anticipated would be needed.**
   `applyPlanInBackground` runs each edit on a dedicated thread (re-reading/rewriting the file
   per edit via the existing `BindingsWriter`), marshalling the result and table refresh back
   onto the EDT — so a large batch doesn't freeze the UI.
6. **Backup-before-apply: unchanged from the existing pipeline, not specially extended for this
   feature.** Each edit goes through the same `BindingsWriter.assignKeyboardKey(...)` path
   already audited in `dawntreader-docs/Reports/2026-06-21_Backup_System_Audit.md` — meaning the
   gaps identified there (no first-run snapshot, no multi-file coverage, no retention limit, no
   restore UI) are **still open and unaddressed by this feature**, not resolved by it. Auto-fill
   makes those gaps more pressing, not less, since it's the feature most likely to produce a
   large batch of edits a user might want to undo at once.
7. **UI implementation followed existing conventions, as instructed** — built directly into
   `BindForgeTabPanel` using the existing table/dialog/i18n patterns, no new UI paradigm. The
   longer-term goal of the editing UI feeling more like the game's own control-binding interface
   (Section E.4's SDL3 "click a slot, press the key you want" capture pattern) is unaffected by
   this — auto-fill and live capture are separate, complementary features.
6. **Architectural constraint:** singletons, not DI (see new Section B bullet) — applies to any
   new detection/fill-logic service built for this.

## G. Backup & Restore (new "Binding Management" tab, designed 2026-06-23)

Genuinely new scope, distinct from Section F — this is a real, missing gap confirmed by the
2026-06-21 backup audit (no first-run snapshot, no multi-file coverage, no retention limit, and
**no restore UI exists at all today**). Krondor isn't building this; it's ours.

**Build status, 2026-06-24:**
- ✅ **BUILT** — tab split (`57dcab6a`): BIND FORGE now has "Binding Profile" / "Binding
  Management" sub-tabs, `BindingProfilePanel` extracted unchanged, `AppView` needed zero
  changes.
- ✅ **BUILT** — backup creation & listing (`ef12e9bb`): `PlayerBackupService` (singleton, not
  DI) sweeps every `.binds` file + `StartPreset.*.start` into a new timestamped folder under
  `playerbackups` (collision-safe `-1`/`-2` suffix on same-second backups), confirmed
  `DeviceMappings.xml` correctly excluded. `AppPaths.getPlayerBackupsDir()` resolves
  `playerbackups` as a sibling to `bindings/`/`db/`/`custom-commands/` via the same
  XDG/LOCALAPPDATA-aware base path, confirmed isolated from the existing per-Apply
  `BindingsBackupService` mechanism. "Binding Management" now has a working "Backup Now" button
  and a flat, newest-first backup list (Created/Files columns). 3 new unit tests, all green.
- ✅ **BUILT** — restore, both targets (`11086754`): `PlayerBackupService.restoreToWorkingCopy()`
  / `.restoreToLive()`. "Restore to Editing Slot" writes the backup's file into the working copy
  via `BindingsWorkingCopyRepository.save()` and publishes `BindingsUpdatedEvent`, which
  `BindingProfilePanel` already reacts to — no new coupling needed. "Restore to Live" does the
  same first step, then calls the exact same `BindingsApplyService.apply()` method/parameters as
  a normal Apply, so it gets the identical conflict-check and pre-write backup. Both run off the
  EDT; both confirm before acting, naming the specific file. A duplicated success/error-dialog
  pattern found by code-integrity review was extracted into a shared
  `BindingApplyResultPresenter`, now used by both `BindingProfilePanel` and this restore path. 3
  new unit tests (8/8 total in `PlayerBackupServiceTest`, all green).
  - **Real scope narrowing, confirmed during implementation — corrects the "whole folder as a
    unit" phrasing below:** `StartPreset.*.start` has no "working copy"/draft concept anywhere
    in the existing codebase — only `.binds` files do. Restoring it would require writing
    directly to the live bindings directory, exactly the less-safe direct-write path this
    design explicitly ruled out. **Restore therefore only covers the `.binds` file for
    whichever preset is currently active** (resolved via the new
    `BindingsMonitor.resolveActiveBindsFile()`) — not `StartPreset.*.start`, and not other
    presets' `.binds` files that might also be sitting in the same backup folder. If the
    selected backup has no file for the active preset, both restore methods fail with a clear
    error rather than silently restoring nothing or guessing a different preset.

- **UI structure:** split the current single "Binding Profile" screen into two tabs — "Binding
  Profile" (today's existing view, unchanged) and a new "Binding Management" tab for everything
  below.
- **Scope of what gets backed up:** every `.binds` file in the user's bindings folder (not just
  the currently-active preset) plus `StartPreset.4.start`. `DeviceMappings.xml`/`.buttonMap` are
  explicitly **deferred** until BindForge does input capture and backups for those file types —
  not part of this feature's scope yet.
- **Location:** a new, dedicated folder — `%LOCALAPPDATA%\elite-intel\playerbackups\` on
  Windows — separate from the existing internal `elite-intel/bindings/backups/` mechanism (see
  below). On Linux/macOS, resolved the same way `AppPaths.getAppDataBase()` already resolves
  EliteIntel's own data folder (`XDG_DATA_HOME`, falling back to `~/.local/share`), so
  `playerbackups` sits as a sibling under that same base rather than introducing a new
  resolution scheme. Deliberately named generically (not `bindingsbackups`) so future features
  (commander profiles, etc.) can add their own subfolders alongside it later.
- **Relationship to the existing automatic per-Apply backup — kept separate, not merged.** The
  existing `BindingsApplyService`/`BindingsBackupService` mechanism (one timestamped `.bak` file
  per Apply, written to `elite-intel/bindings/backups/`) serves a different purpose — an
  internal safety net tied to the apply-pipeline's own conflict-detection logic — and stays
  exactly as it is. `playerbackups` is a separate, additional, user-facing feature on top, not a
  replacement or redirection of that existing path.
- **Identity:** one flat list of backups for now (no grouping by preset) — acknowledged this
  will likely need revisiting as the feature expands, but flat is the right starting point.
- **Physical layout: one timestamped folder per backup event** (e.g.
  `playerbackups\2026-06-23_14-30-00\`), containing the original files with their real
  filenames intact — not zipped. A "backup" is one folder; restoring means restoring that whole
  folder's contents as a unit. Zip bundling was considered and explicitly deferred — there's a
  real future need for it once `DeviceMappings`/`.buttonMap` (or other file types) make
  "many small files per snapshot" a bigger concern, but that's expected to land much later
  (tentatively EliteIntel v2+), not part of this design.
- **Trigger:** a manual "Backup Now" action, on demand — not just the existing automatic
  per-Apply trigger. Creates a new timestamped folder snapshot of the current live `.binds`
  files + `StartPreset.4.start`.
- **Restore — two targets, both sharing the same first step. As shipped, this restores the
  `.binds` file for the active preset only, not the whole backup folder as a unit — see the
  "real scope narrowing" note above for why.**
  1. Loading the selected backup's `.binds` file (for the currently-active preset) into the
     working copy (`elite-intel/bindings/`) as the new draft — this is the shared mechanism
     behind both restore options.
  2. **Restore to editing slot** stops there — the user can review/further-edit the restored
     content before deciding whether to Apply, same as importing any other draft.
  3. **Restore to live** continues immediately into the existing, already-safe `Apply` pipeline
     (`BindingsApplyService.apply()`) — meaning a restore-to-live still gets the existing
     conflict-check and the existing pipeline's own pre-write backup of whatever's currently
     live, rather than a separate, less-safe direct-write path. Confirmed by the user 2026-06-23:
     restore-to-live should go through the safe apply pipeline, not bypass it.
- **Architectural constraint:** singletons, not DI (same as Section B/F) — applies to any new
  backup/restore service built for this.
- **Still open, not yet designed:** the actual UI layout of the new tab (table columns, button
  placement), and the concrete Java class/method shapes for the new backup/restore service.

---

*Last updated from planning session — fold in future findings as they're settled.*
