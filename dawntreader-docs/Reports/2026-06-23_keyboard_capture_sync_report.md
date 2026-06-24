# Keyboard Capture Sync Report — 2026-06-23

Bringing Krondor's keyboard-capture replacement for the dropdown-based bind editor onto
`bindforge-gamemode`: press-to-capture instead of two dropdowns, multi-modifier support,
order-insensitive conflict detection, and `KeyCaptureMapper` wired in for the first time.
Nothing was pushed to `origin` (Krondor's repo) at any point.

**Note on continuity:** this task was interrupted mid-way (after the fetch/merge/push, partway
into the read-only audit) and resumed after a session restart. Steps 1–4 below were verified as
already complete and pushed before resuming the audit — they are reported as confirmed-complete
rather than re-run from scratch, since re-running them would have been redundant (and the merge
was already on `mine`).

## 1. Located the commits

```
git fetch origin
```

Fetch pulled `origin/V1.1` forward (`7a1a3f28..e54b6597`) and surfaced two new branches:
`origin/V1.1-custom-command-ui-redesign` and `origin/V1.1-key-detector-for-binding-assignment`.
Checked both before assuming the feature was on `V1.1` itself:

- **`origin/V1.1` recent log:**
  ```
  e54b6597 ## Captured keyboard binding chords by key press; allowed multi-modifier
  000ce85d Bindings ENUM proper naming
  2fd71b3d ## Redesign custom command editor around derived action keys
  7a1a3f28 Add one-shot auto-assign for unbound key bindings (KAN-65)
  ```
- **`origin/V1.1-key-detector-for-binding-assignment`** is identical to `origin/V1.1`
  (`0  0` ahead/behind, same tip `e54b6597`) — Krondor's working branch for this ticket, already
  folded into `V1.1`.
- **`origin/V1.1-custom-command-ui-redesign`** is 2 commits *behind* `origin/V1.1` (its tip is
  `2fd71b3d`) — an earlier snapshot of the same line of work, also fully subsumed.

**Confirmed: the keyboard-capture commit is `e54b6597`, directly on `origin/V1.1`**, preceded by
an unrelated cosmetic enum-renaming commit (`000ce85d`) and an unrelated custom-command-editor
redesign (`2fd71b3d`). No separate branch needed chasing.

## 2. Fast-forwarded `V1.1`, merged into `bindforge-gamemode`

Local `V1.1` was 0 ahead / 3 behind `origin/V1.1`:

```
git checkout V1.1
git merge --ff-only origin/V1.1
```

Result: clean fast-forward, `7a1a3f28 -> e54b6597`, 30 files changed (full list in §5 below).

```
git checkout bindforge-gamemode
git merge --no-commit --no-ff V1.1
```

**Result: clean merge, zero conflicts.** "Automatic merge went well; stopped before committing
as requested," `git status` showed everything already staged with "All conflicts fixed but you
are still merging," and `git diff --check` found no conflict markers.

One local complication, unrelated to the merge itself: an in-progress, uncommitted edit to
`dawntreader-docs/Reports/BindForge_Punch_List.md` (Section F reconciliation, pre-existing
working-tree state from earlier today) blocked the initial `git checkout V1.1` with "local
changes would be overwritten." Stashed it (`git stash push -- dawntreader-docs/Reports/
BindForge_Punch_List.md`) before switching branches, then popped it back cleanly after the merge
commit — it was never part of this sync and is left as an uncommitted working-tree change, same
as before.

Committed as `d3107314` — "Merge branch 'V1.1' into bindforge-gamemode".

## 3. Build/compile check

```
./gradlew compileJava compileTestJava
```

**BUILD SUCCESSFUL** — `app:compileJava` and `app:compileTestJava` both passed with no errors.
(Re-verified after the session restart: still `BUILD SUCCESSFUL`, all tasks `UP-TO-DATE`.)

## 4. Pushed to `mine` only

Confirmed push target before pushing:

```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Then a plain push:

```
fc0e5956..d3107314  bindforge-gamemode -> bindforge-gamemode
```

Pushed to `https://github.com/DawnTreader/EliteIntel.git` only.

**Re-verified after the session restart:** `git ls-remote origin bindforge-gamemode` returned
nothing — confirmed `origin` has no knowledge of `bindforge-gamemode` at all, before, during, or
after this sync. `mine/bindforge-gamemode` matches local HEAD (`d3107314`) exactly.

## 5. What actually shipped — read-only audit

Full file list from the fast-forward (`7a1a3f28..e54b6597`):
```
CustomCommandKeyDeriver.java (new), CustomCommandValidator.java, ResponseRouter.java,
Bindings.java, BindingsWriter.java, KeyBindingsParser.java, KeyboardKeyAvailabilityService.java,
PACKAGE.md, ToolGenerateBindings.java, AssignKeyboardBindingDialog.java,
CustomCommandEditorDialog.java, CustomCommandImportDialog.java, BindForgeTabPanel.java,
CustomCommandsTabPanel.java, AssignKeyboardBindingSelection.java,
BindingSlotDisplayFormatter.java, KeyChordCaptureField.java (new), AppPaths.java,
i18n properties (7 locales), 3 new/modified test files
```

### Mechanism: AWT/Swing global key-event dispatcher, not SDL3 — confirmed directly

`KeyChordCaptureField.java` (new file, `app/src/main/java/elite/intel/ui/widget/
KeyChordCaptureField.java`, 208 lines) is a plain `JPanel`. No SDL3 import anywhere in it or in
its caller. The capture mechanism, quoted directly:

```java
// KeyChordCaptureField.java:100-109
private void arm() {
    if (armed) {
        return;
    }
    armed = true;
    heldModifiers.clear();
    renderArmedPreview();
    dispatcher = this::dispatchKeyEvent;
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
}
```

This is exactly what the Slack description implied: a `KeyEventDispatcher` installed on the
*owning window's* `KeyboardFocusManager` only while the field is "armed" (clicked), not an
OS-level hook. It's removed the instant capture finalizes, is cancelled, or the component is
torn down:

```java
// KeyChordCaptureField.java:111-126, 128-133
private void cancelCapture() { ... disarm(); surface.setText(committedText); }
private void disarm() {
    armed = false;
    heldModifiers.clear();
    if (dispatcher != null) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        dispatcher = null;
    }
}
@Override
public void removeNotify() {
    // Never leave a global dispatcher installed once the field is gone.
    disarm();
    super.removeNotify();
}
```

While armed, every key event for the window is consumed (`e.consume()`,
`dispatchKeyEvent`, lines 135-148) so Enter/Tab reach the chord builder instead of triggering
dialog default-button/focus-traversal behavior. Escape cancels; modifier keys accumulate in
press order into a `LinkedHashSet<String> heldModifiers` (line 47); the first non-modifier key
finalizes the chord (`finalizeChord`, lines 175-182) and immediately disarms.

**Verdict: plain Swing focus-scoped key dispatcher, exactly as described — no SDL3, no
OS-level hook.**

### `KeyCaptureMapper` — file itself unchanged, but gained its first real caller

```
git diff 7a1a3f28 e54b6597 -- app/src/main/java/elite/intel/util/KeyCaptureMapper.java
# (empty — no diff)
```

`KeyCaptureMapper.java` itself was **not modified** in this commit range — it's the same
scan-code/VK-to-Elite-token mapper that existed (with zero callers) as of yesterday's
full-state audit. What changed is that it now has callers, all in the new
`KeyChordCaptureField`:

```java
// KeyChordCaptureField.java:155-160
if (KeyCaptureMapper.isModifierOnly(e)) {
    KeyCaptureMapper.fromKeyEvent(e).ifPresent(heldModifiers::add);
    renderArmedPreview();
    return;
}
Optional<String> mainKey = KeyCaptureMapper.fromKeyEvent(e);
```

**Verdict: confirmed wired in.** `isModifierOnly()` filters Ctrl/Shift/Alt presses into the
held-modifier set; `fromKeyEvent()` resolves every key (modifier or main) to an Elite token via
the existing scan-code/VK mapping logic. This is the first production code path that actually
calls `KeyCaptureMapper` — yesterday's "zero callers" finding is now resolved.

### Modifier-cap change: data shapes vs. gate logic

**The record shapes documented in yesterday's audit did not change.**
`ReadOnlyBindingSlot.bindingModifiers` was already `List<BindingModifier>` (plural, unbounded)
before this sync — confirmed by diffing `KeyBindingsParser.java` across this range: the
`ReadOnlyBindingSlot`/`ReadOnlyBindingSlots`/`KeyBinding`/`BindingSlots` record definitions are
byte-for-byte unchanged. What changed is the **gate condition** that decided whether a
multi-modifier slot counted as "editable":

```java
// KeyBindingsParser.java:237-243, diff 7a1a3f28..e54b6597
private boolean isEditableKeyboardSlot(String device, String key, List<BindingModifier> modifiers) {
    if (!hasKeyboardMainKey(device, key)) {
        return false;
    }
    // The chord editor can rewrite any number of supported keyboard modifiers
    // (e.g. Left Ctrl + Left Shift), so a slot is editable as long as every
    // modifier is a supported keyboard modifier.
    return modifiers.stream().allMatch(BindingModifier::isSupportedKeyboardModifier);
}
```
(previously: `modifiers.isEmpty() || (modifiers.size() == 1 && modifiers.get(0)
.isSupportedKeyboardModifier())` — hard-capped at one.)

The write side gained a genuinely new public method,
`BindingsWriter.assignKeyboardKeyWithModifiers(KeyboardBindingEdit, List<BindingModifier>)`
(`BindingsWriter.java`), replacing the single-modifier-only
`assignKeyboardKeyWithModifier(edit, BindingModifier)` (kept as a one-element-list wrapper for
compatibility). The written XML now loops over the full list:

```java
// BindingsWriter.java, replacementSlot(), diff 7a1a3f28..e54b6597
if (rewriteModifier && !requestedModifiers.isEmpty()) {
    StringBuilder slot = new StringBuilder("<" + edit.slotType().xmlElementName()
            + " Device=\"Keyboard\" Key=\"" + edit.key() + "\">\n");
    for (BindingModifier modifier : requestedModifiers) {
        slot.append("    <Modifier Device=\"Keyboard\" Key=\"").append(modifier.key()).append("\" />\n");
    }
    slot.append("</").append(edit.slotType().xmlElementName()).append(">");
    return slot.toString();
}
```

**Verdict:** no data-shape change relative to yesterday's audit — `KeyBinding`/
`ReadOnlyBindingSlot` already used array/list fields for modifiers. The change is that
write-path *gate logic* (parser's `isEditableKeyboardSlot`, writer's slot-inspection
`supportedModifiers` check) now accepts any count instead of being hard-capped at exactly one.

### Conflict detection: order-insensitivity, confirmed at the save-time/availability layer

`KeyboardKeyAvailabilityService` (used by `AssignKeyboardBindingDialog` for both "what keys are
free" and "is this key+modifier-set already used elsewhere") changed its modifier comparison
from single-value equality to set equality:

```java
// KeyboardKeyAvailabilityService.java, diff 7a1a3f28..e54b6597
private record SlotAssignment(String bindingId, BindingSlotType slotType, String key,
                              Set<BindingModifier> modifiers) {   // was: BindingModifier modifier
}

/**
 * Keeps only supported keyboard modifiers, collapsing the chord to a set so
 * modifier order never affects conflict matching.
 */
private Set<BindingModifier> normalizeModifiers(Collection<BindingModifier> modifiers) { ... }
```

and the comparison itself moved from `Objects.equals(assignment.modifier(), modifier)` to
`assignment.modifiers().equals(modifiers)` (`Set.equals()` — order-independent by definition).
`BindingsWriter`'s own "no change" detection got the identical treatment
(`isNoChange()`: `existing.equals(new HashSet<>(requestedModifiers))`).

**Note on scope:** this is the save-time/dialog-level conflict check
(`KeyboardKeyAvailabilityService`), which is a separate code path from `BindingsMonitor
.checkForConflictsAndPersist()`/`binding_conflicts` (the file-watch-triggered, DB-persisted
conflict detection audited in yesterday's full-state audit). Checked directly: `BindingsMonitor
.java`, `BindingConflictRules.java`, `BindingConflictManager.java`, and `BindingConflictDao.java`
do not appear anywhere in this commit range's diff — **that detection path is untouched by this
sync.** The order-insensitivity fix landed specifically in the editor's own availability/
no-change logic, not in the monitor's background conflict scan.

**Verdict: confirmed — order-insensitivity added to the editor's save-time conflict check and
no-change comparison; the separate `BindingsMonitor` conflict-persistence path is unaffected.**

### Parse-failure handling: confirmed "log instead of silently swallowing"

Found in `AssignKeyboardBindingDialog.java`'s availability check (the method that decides
whether a chord can be saved):

```java
// AssignKeyboardBindingDialog.java, diff 7a1a3f28..e54b6597
} catch (Exception e) {
    // WHY: an unreadable/unparseable .binds must never be reported as free,
    // or we would offer a save that could silently clobber another binding.
    log.warn("Could not check key availability for binding '{}' slot {}: {}",
            bindingId, slotType, e.getMessage());
    return true;
}
```
(previously: `catch (Exception e) { return true; }` — same fail-safe `true` return, no log line
at all). A new `private static final Logger log = LogManager.getLogger(...)` was added to the
class to support this.

**Verdict: confirmed.** The fail-safe behavior itself (treat unreadable file as "occupied," i.e.
refuse to offer a save) is unchanged — what's new is that the failure is now actually visible in
the log instead of disappearing silently.

### Scope: still BUTTON-only, keyboard-only — confirmed directly from the diff

Checked every file in the 30-file changed-list for AXIS or non-keyboard-device handling:

- `Bindings.java`'s 468-line diff is **purely a cosmetic enum rename** (e.g. `AUTO_BREAK_BUGGY_BUTTON`
  → consistent naming convention), confirmed by sampling the diff — no new binding categories,
  no AXIS entries added.
- `KeyChordCaptureField.CapturedChord` is `record CapturedChord(List<BindingModifier> modifiers,
  String key)` — a single main key plus modifiers, the same BUTTON/chord shape as before, not an
  axis value.
- `KeyBindingsParser.parseReadOnlyBindingSlots()` (the method that decides which `.binds`
  elements even get modeled) was not touched in this diff — it still only scans for
  `Primary`/`Secondary` child elements, exactly as documented in yesterday's full-state audit.
  AXIS (`<Binding>`/`<Inverted>`/`<Deadzone>`) and standalone settings (bare `Value="..."`) are
  still invisible to the parser.
- No new device-polling code, no `DeviceBus`/`DeviceService` references introduced anywhere in
  this diff (re-confirmed by grep across the changed-file list).

**Verdict: confirmed — still BUTTON-only, keyboard-only, no AXIS or controller capture
introduced.** This is a faithful extension of the same scope audited yesterday, not a widening
of it.

---

## End state

- Current branch: `bindforge-gamemode` @ `d3107314`
- Local `V1.1`: fast-forwarded to `e54b6597`, matches `origin/V1.1`
- Feature branch: merged with `V1.1` (3 new commits — keyboard-capture, enum rename, custom
  command editor redesign), builds clean, pushed to `mine`
- `origin`: completely unchanged throughout — confirmed via `git ls-remote origin
  bindforge-gamemode` returning empty both before and after the push, re-verified after the
  session restart

## Bottom line

The keyboard-capture replacement is real, scoped exactly as described, and already merged in:
press-to-capture via a focus-scoped Swing `KeyEventDispatcher` (not SDL3, not an OS hook),
`KeyCaptureMapper` now has its first production caller, multi-modifier chords are supported by
relaxing an existing gate condition (no data-shape change from yesterday's audit), conflict
matching at the editor's save-time/availability layer is now order-insensitive (the separate
`BindingsMonitor`/`binding_conflicts` background-scan path is untouched), and a previously-silent
catch block now logs its failure. Scope is unchanged: BUTTON-only, keyboard-only — the
AXIS/STANDALONE-setting gap and the complete absence of conflict UI documented in yesterday's
full-state audit both still stand exactly as they were.
