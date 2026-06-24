# Autofill Sync Report — 2026-06-23

Bringing Krondor's keyboard auto-assign ("Fix Missing") feature for BindForge onto
`bindforge-gamemode`, so we can plan against the real implementation instead of our own
planning notes. Nothing was pushed to `origin` (Krondor's repo) at any point.

## 1. Located the feature

```
git fetch origin
```

Fetch pulled `origin/V1.1` forward (`c5651efb..7a1a3f28`) and surfaced a brand-new branch,
`origin/V1.1-missing-key-bind-mapping`. Checked both:

- **`origin/V1.1` recent log** shows two commits with the feature directly on it:
  ```
  7a1a3f28 Add one-shot auto-assign for unbound key bindings  (KAN-65)
  3034a06a Add one-shot auto-assign for unbound key bindings  (KAN-65)
  44d0dfd2 removing vanity cam / shorter scroll up time
  cbaa6393 adding remaining possible binds to enum
  200e84e8 Validate data directories and localize operational speech
  c5651efb start Mouth and Ears before aux and journal monitor
  ```
- **`origin/V1.1-missing-key-bind-mapping`** is identical to `origin/V1.1`
  (`git rev-list --left-right --count` → `0  0`, same tip `7a1a3f28`) — it's Krondor's working
  branch for this ticket, already folded straight into `V1.1`, not a separate line of work to
  chase down.

**Confirmed: the feature is on `origin/V1.1` itself.** No other branch needed.

## 2. Fast-forwarded `V1.1`, merged into `bindforge-gamemode`

Local `V1.1` was 0 ahead / 6 behind `origin/V1.1`, so straightforward:

```
git checkout V1.1
git merge --ff-only origin/V1.1
```

Result: clean fast-forward, `bccac490 -> 7a1a3f28`. 22 files changed, including two brand-new
production classes (`MissingBindingAutoAssigner.java`, `SafeKeyboardKeys.java`) and three new
test files.

```
git checkout bindforge-gamemode
git merge --no-commit --no-ff V1.1
```

**Result: clean merge, zero conflicts.** "Automatic merge went well; stopped before committing
as requested," `git status` showed everything already staged with "All conflicts fixed but you
are still merging," and `git diff --check` found no conflict markers.

Committed as `fc0e5956` — "Merge branch 'V1.1' into bindforge-gamemode".

## 3. Build/compile check

```
./gradlew compileJava compileTestJava
```

**BUILD SUCCESSFUL** — `app:compileJava` and `app:compileTestJava` both passed with no errors.

## 4. Pushed to `mine` only

Confirmed push target first:

```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Then a plain push:

```
d7e8463d..fc0e5956  bindforge-gamemode -> bindforge-gamemode
```

Pushed to `https://github.com/DawnTreader/EliteIntel.git` only.

Re-fetched `origin` afterward — `git ls-remote origin bindforge-gamemode` returned nothing
both before and after the push. **Confirmed: `origin` has no knowledge of `bindforge-gamemode`
at all**, and `mine/bindforge-gamemode` now matches local HEAD (`fc0e5956`).

## 5. What actually landed — auto-assign feature summary

Two new classes implement the feature, both in `elite.intel.ai.hands`:

### `MissingBindingAutoAssigner.java` (206 lines, new)

Pure, file-free planner — produces a plan of edits without writing anything itself (the caller,
`BindForgeTabPanel`, does the actual write via the existing `BindingsWriter`).

- **`isKeyboardBound(ReadOnlyBindingSlots)`** — the single shared definition of "bound": true if
  either Primary or Secondary slot is keyboard-usable. This is now the *same* predicate the
  Missing/Used tab split uses (see below), so the UI and the auto-assigner can never disagree
  about what counts as missing.
- **`planAll(slots)`** — plans assignments for every unbound keyboard-capable control in the
  file (`Map<String, ReadOnlyBindingSlots>` → `Plan`), in stable case-insensitive order.
- **`planOne(bindingId, slots)`** — same logic for a single control (the per-row "auto fix"
  click); returns an empty plan if already bound or unknown.
- **Two invariants, enforced directly in code:**
  - *Add, never replace* — an edit is only produced for an empty (`{NoDevice}`) slot;
    controller/HOTAS and existing keyboard assignments are never touched
    (`isWritableEmpty`/`chooseWritableSlot`).
  - *Never collide* — a chord (key + optional modifier) is used at most once: checked against
    every chord already in the file (`occupiedChords`) and against everything already planned
    in the current batch.
- **`Plan(edits, skipped)`** record — `edits` are `PlannedEdit(bindingId, slotType, key,
  modifier)`; `skipped` are `SkippedBinding(bindingId, SkipReason)` with three reasons:
  `BOTH_SLOTS_OCCUPIED`, `NO_EDITABLE_SLOT`, `NO_FREE_KEY`.

### `SafeKeyboardKeys.java` (93 lines, new)

The "safe key pool" the assigner draws from — separate from `EliteKeyboardKeys` (the full
allow-list used for *manual* assignment). Restricted to keys/chords that sit at the same
physical position with the same label across QWERTY, AZERTY, and QWERTZ, since `.binds` only
records the game's locale, not the user's physical layout.

- **Included:** letters `E R T U I O P S D F G H J K L B N` (deliberately excludes `Q W A Z M Y`
  — they move between layouts), digits `0-9`, full numpad, `F1-F12`.
- **Safe modifiers:** `LeftControl`, `LeftShift`, `LeftAlt`, `RightShift` — `RightAlt` is
  excluded because it's AltGr on AZERTY/QWERTZ.
- **`orderedChords()`** — allocation order is every base key × every safe modifier first, *then*
  plain unmodified keys, so combos get used up before "burning" a bare letter a commander might
  want free for something else later.

### UI wiring — `BindForgeTabPanel.java` (+236/-40 lines)

- New `fixAllButton` ("Fix Missing") added to the footer next to Revert/Apply; enabled only when
  the Missing tab is non-empty.
- New per-row auto-fix action: the Missing table gained a 4th column
  (`bindings.column.autofix`), wired through `BindingsGroupTableFactory`'s new `autoFixHandler`
  constructor param — clicking that cell calls `autoFixSingleBinding(bindingId)`.
- `fixAllMissing()` confirms via a dialog, then calls `autoAssigner.planAll(currentSlots)` and
  applies the plan **off the EDT** (`applyPlanInBackground`, dedicated
  `"BindingsAutoFix-Thread"`) — each edit re-reads/rewrites the file via the existing
  `BindingsWriter.assignKeyboardKey(...)`/`assignKeyboardKeyWithModifier(...)`, so a large batch
  doesn't freeze the UI. Result and table refresh (`initData()`) are marshalled back onto the
  EDT.
- After applying, shows a summary dialog (`showBatchSummary`/`showSingleResult`) breaking down
  saved/failed/skipped counts by `SkipReason`.
- **Notable scoping change:** `initData()` no longer drives the Used/Missing split from
  `BindingsMonitor.findFoundGameBindings()`/`findMissingGameBindings()` (the EliteIntel-only
  `Bindings.GameCommand` scope we documented in the 2026-06-23 missing-bindings audit). It now
  uses a new local `partitionByKeyboardBinding()` against **every keyboard-capable control in
  the file**, via `autoAssigner.isKeyboardBound(...)`. The narrower EliteIntel-only count is
  still computed and published separately (`BindingsSummaryChangedEvent`, for the AI tab badge)
  — so there are now *two* notions of "missing" living side by side: the broad one driving these
  tables/auto-assign, and the narrow EliteIntel-dispatch one driving the voice/log
  announcements we audited previously (`BindingsMonitor`/`KeyBindCheck`, unchanged by this
  commit).

### Supporting/incidental changes in the same commits (not part of auto-assign itself)

- `DataDirectoryValidator.java` (new, `elite.intel.gameapi`) + wiring in `BindingsMonitor`,
  `BindForgeTabPanel`, `CommonSettingsPanel` — validates/warns on bindings & journal directory
  health; unrelated feature bundled into the same push.
- `Bindings.java` (+187 lines) — "adding remaining possible binds to enum" per the commit log;
  expands `Bindings.GameCommand` with more Frontier action entries. Not consumed by the new
  auto-assigner (which works off the raw `.binds` file's full slot map, not this enum) but
  relevant background for the EliteIntel-required-bindings scoping question.
- Misc: `RequestDockingCommand.java`, `JournalParser.java`, `MissingMissionMonitor.java`,
  `AppController.java` — small unrelated fixes folded into the same `V1.1` history.

### Tests

`MissingBindingAutoAssignerTest.java` (275 lines, 11 cases) covers exactly the invariants above:
empty-slot assignment, controller-primary-falls-back-to-secondary, both-slots-occupied skip,
already-bound is left alone, joystick axes aren't targeted, occupied-chord skip/next-free-chord,
no-reuse-within-a-batch, pool-exhaustion (`NO_FREE_KEY`), `planOne` single-binding behavior, and
that every planned chord comes from the safe pool. `SafeKeyboardKeysTest.java` (72 lines) and
`DataDirectoryValidatorTest.java` (75 lines) cover the other two new classes.

## Versus `BindForge_Punch_List.md` Section F

This is now real code to plan against rather than speculation — worth a follow-up pass
comparing this implementation point-by-point against whatever Section F currently says, since
at minimum the "two notions of missing" scoping change above (broad keyboard-capable-controls
vs. narrow EliteIntel-dispatch) is a concrete design fact that may not match what was assumed
there.

## End state

- Current branch: `bindforge-gamemode` @ `fc0e5956`
- Local `V1.1`: fast-forwarded to `7a1a3f28`, matches `origin/V1.1`
- Feature branch: merged with `V1.1`, builds clean, pushed to `mine`
- `origin`: completely unchanged throughout — confirmed via `git ls-remote origin
  bindforge-gamemode` returning empty before and after the push
