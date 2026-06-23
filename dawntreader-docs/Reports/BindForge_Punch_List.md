# BindForge — Running Punch List

**Status:** Planning consolidation. Not a spec — a tracking list of decided items and open items to work through before/during data model design.

---

## A. Settled — `.binds` Schema (fully evidence-backed)

- Three element types: AXIS (`<Binding>` + optional `Inverted`/`Deadzone`), BUTTON (`<Primary>`/`<Secondary>` + optional element-wide `<ToggleOn>`), STANDALONE SETTING (bare `Value=`, no slots).
- Slot-level properties apply to any `Primary`/`Secondary`/`Binding` slot:
  - `Modifier`: zero to **three** per slot (hard cap — 4th overflows into becoming the base key). Any device type (not keyboard-only — confirmed via `AutoBreakBuggyButton`/RVWAP joystick-as-modifier and the Virpil slider end-of-travel button quirk). Left/Right tracked as distinct values, freely mixable. Same key can't be both base and modifier on one slot.
  - `Hold`: per-slot, boolean-ish (`Value="1"` observed), origin is *how the key was captured* (held ~1+ sec during detection vs. tapped), not a user-chosen setting. Never appears on AXIS. Independent of and can coexist with `ToggleOn` on the same element (confirmed via `ToggleCargoScoop`).
  - `ToggleOn`: element-level (covers both Primary and Secondary as one shared behavior, not per-slot). Only two observed values (0/1). BUTTON-only, never AXIS.
  - `Deadzone`: AXIS-only, range 0–1, never on BUTTON.
  - `Inverted`: AXIS-only.
- Edge cases confirmed in full-file extraction (`AUDIT_v2_FULL.md`): duplicate element names possible (`MouseGUI` ×2); half-axis/mouse-wheel key strings (`Neg_Joy_YAxis`, `Pos_Mouse_ZAxis`) can appear on BUTTON slots, not just AXIS; named devices beyond RVWAP/Keyboard/Mouse exist (T-Rudder, LVWAP, vJoy) — device field must accept arbitrary names; one non-conforming node (`KeyboardLayout`, text-content not attribute) needs its own handling path; element *names* are not reliable type indicators (`*ButtonPartial` elements classify as AXIS).
- Source of truth = the `.binds` XML schema itself, not any single file's populated/unpopulated state. Audit files are schema specimens, not personal-config trackers.
- `.binds` has exactly **one** canonical file location system-wide (`%LOCALAPPDATA%\Frontier Developments\Elite Dangerous\Options\Bindings\`) — shared across all storefront installs, no multiplicity.
- `DeviceMappings.xml` + `.buttonMap` files are cosmetic-only (button labeling), **not** game config — but are duplicated per-installation (one full copy under each storefront's own `ControlSchemes` folder). Multiplicity confirmed real (Steam + Epic side by side).

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

## F. Open — Onboarding: Missing Key Bind Detection & Auto-Fill (new scope, 2026-06-21 Krondor meeting)

Krondor wants to scope down the first BindForge publish from everything previously planned in
this document and focus on two things first: onboarding (this section) and a more game-like
editing interface (folded into Section E.4's keyboard-capture work above). He liked the broader
ideas but wants this narrower slice shipped first.

1. **What "missing" actually means, clarified 2026-06-21:** this is not about every possible
   game action being unassigned — it's specifically about **EliteIntel's own command-dispatch
   reliability**. EliteIntel sends voice-triggered commands to the game by simulating the
   keypresses for whatever `.binds` entry that action maps to. If that entry's slot is
   `{NoDevice}` (unassigned), the keypress simulation has nothing to send, and that voice command
   silently fails in-game. So "missing" = `{NoDevice}`/empty slots specifically among the subset
   of `.binds` elements EliteIntel's own command set depends on — not a general audit of every
   action in the schema.
   - **Krondor's direct instruction, 2026-06-21: this detection may already exist.** He's pointed
     CCTIJ at two specific methods to examine in `elite.intel.ai.hands` —
     `findMissingGameBindings()` and `checkForMissingBindingsAndPersist()` — as the basis for
     building the missing-bindings list, rather than designing detection from scratch. **Open
     verification item, not yet confirmed:** whether these methods exist verbatim today (in which
     case this feature is substantially building on existing logic) or are intended names for
     something still to be built. This needs checking before scoping the work, since it changes
     how much of this is "wire up existing detection" versus "build detection from zero."
2. **Two fill paths, both wanted, presented as an explicit user choice — refined 2026-06-21:**
   the flow is detect → notify the user they have missing key bindings → show a choice dialog
   ("Auto-fill these for me" vs. "Let me assign each one myself") → carry out whichever the user
   picked → confirmation dialog before anything is actually applied → persist through the
   existing working-copy/apply pipeline (Section B). This is a real UI screen, not just two
   buttons living side by side — the user picks a path up front, then that path runs to
   completion (either the full auto-fill set goes to confirmation together, or the user is walked
   through manually assigning each missing entry one at a time via the same capture mechanism as
   general editing, Section E.4).
   - **Automatic path** — generate values via the algorithm in item 3 below.
   - **User-chosen path** — let the user manually assign each one themselves.
3. **Auto-fill key/modifier selection — now fully specified by Krondor, 2026-06-21, not just a
   general principle:** generate combination bindings using `LeftCtrl`/`LeftShift`/`RightShift`/
   `LeftAlt` as modifiers plus a base key drawn **only** from the set of letters that sit in the
   same physical position with the same printed label across QWERTY, QWERTZ, and AZERTY layouts:
   `E R T U I O P S D F G H J K L B N`. Explicitly avoid `Q W A Z Y M` (these shift position or
   meaning between layouts) and all punctuation keys (layout-dependent, error-prone). This
   directly satisfies the earlier "don't steal desirable keys" principle with a concrete,
   international-layout-safe pool rather than leaving it as a vague preference.
4. **Backup-before-apply and restore-from-backup are required**, but the *existing* backup
   mechanism is flagged as needing reassessment first — see the new Section D bullet above and
   the CCTIJ research prompt for this section. (Confirmed 2026-06-21 by the actual backup audit:
   the active Apply-time backup mechanism is sound in isolation, but has no first-run snapshot, no
   multi-file coverage, no retention limit, and **no restore UI exists at all today** — "Revert"
   only discards the in-app draft, it does not restore from a backup file. All of this still needs
   building, not just reassessing.)
5. **UI implementation, per Krondor's instruction 2026-06-21:** follow the existing
   `elite.intel.ui.screen` package's UI controls/theme conventions exactly, and use the existing
   localization (i18n properties) system for any new UI text — no new UI paradigm for this
   feature, just the missing-bindings notification, the auto-fill-vs-manual choice dialog, a
   "generate missing bindings" button on the BindForge tab, and the confirmation dialog before
   applying. Separately, the longer-term goal of the editing UI feeling more like the game's own
   control-binding interface (Section E.4's "click a slot, press the key you want" capture
   pattern) still stands as the broader direction once SDL3 keyboard capture is live — this item
   is about the *onboarding* UI specifically, not a contradiction of that broader goal.
6. **Architectural constraint:** singletons, not DI (see new Section B bullet) — applies to any
   new detection/fill-logic service built for this.

---

*Last updated from planning session — fold in future findings as they're settled.*
