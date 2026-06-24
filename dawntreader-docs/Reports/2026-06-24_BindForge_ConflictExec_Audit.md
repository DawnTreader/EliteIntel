# BindForge Conflict-Detection / Identity-Exec Audit — 2026-06-24

Read-only investigation of `dea8365b` ("Added binding conflict detection, live keyboard map,
identity-based chord exec"), now merged onto `bindforge-gamemode`. No code was written or
modified. Diff range throughout: parent `e54b6597` → `dea8365b`.

## TL;DR

- **Identity-based chord execution: confirmed, exactly as described.** `KeyBindingExecutor` now
  pools every key from both XML slots, classifies each by its own identity (Ctrl/Shift/Alt =
  held, everything else = the one trigger to tap), and no longer trusts which XML slot a key
  was parked in. This is the fix for the modifier-mislabeling bug, built precisely as Krondor
  said he would build it.
- **Subset-key-set suppression: NOT addressed — because Krondor's own in-game testing
  concluded the bug doesn't exist.** `BindingConflictScanner`'s class doc explicitly states an
  earlier "bare key swallows modified chords" model was tested in-game and disproved; the
  symptom that originally suggested it was a stale `.binds` reload, not a real conflict. The
  shipped scanner only flags **exact**, identical key-set matches — confirmed by two dedicated
  tests asserting subsets explicitly do *not* conflict. **This directly contradicts pitfall #2
  in `BindForge_Binding_Rules_Reference.md` and needs to be corrected or re-investigated before
  we touch that document again** — see the bottom line.
- **`KeyboardAvailabilityView` is a new live-coloring QWERTY widget, not a replacement for
  `KeyboardKeyAvailabilityService`.** The two coexist: `KeyboardKeyAvailabilityService` still
  gates the existing exact-occupancy/"already in use" save check (now made context-aware);
  `KeyboardAvailabilityView` is a new visual layer in the assign dialog that colors every key by
  `BindingConflictScanner.candidateConflict(...)` results live as modifiers are held.
- **Conflict UI now exists** — red/green action-name coloring in the BindForge table (recomputed
  on every load), a conflict-naming label in the assign dialog that blocks Save, and the live
  keyboard widget's own coloring. This supersedes the "voice/log-only" finding from yesterday's
  full-state audit, for the new exact-chord conflict type specifically.
- **Scope unchanged: still BUTTON-only, keyboard-only.** Confirmed directly — `KeyBindingsParser.java`
  was not touched at all in this commit, and the only `AXIS` string anywhere in the diff is
  `BoxLayout.Y_AXIS` (a Swing layout constant, unrelated).

## 1. `KeyBindingExecutor` — identity-based chord execution, confirmed

`KeyBindingExecutor.java`'s `executeTap`/`executeBindingWithHold` no longer trust
`binding.key`/`binding.modifiers` at face value. Both now route through a new
`normalizeChord()`:

```java
// KeyBindingExecutor.java, normalizeChord() — package-private, pure
static NormalizedChord normalizeChord(String primaryKey, String[] modifierTokens) {
    // A set so a token listed in both a primary and a modifier slot is held/tapped once, not twice.
    Set<String> pool = new LinkedHashSet<>();
    if (primaryKey != null && !primaryKey.isBlank()) pool.add(primaryKey);
    if (modifierTokens != null) {
        for (String token : modifierTokens) {
            if (token != null && !token.isBlank()) pool.add(token);
        }
    }

    List<String> heldModifiers = new ArrayList<>();
    List<String> triggers = new ArrayList<>();
    for (String token : pool) {
        if (isModifierKey(token)) heldModifiers.add(token);
        else triggers.add(token);
    }

    if (triggers.isEmpty()) {
        return new NormalizedChord(null, heldModifiers, NormalizedChord.Status.NO_TRIGGER);
    }
    if (triggers.size() == 1) {
        return new NormalizedChord(triggers.get(0), heldModifiers, NormalizedChord.Status.OK);
    }
    // ambiguous: 2+ non-modifier keys — prefer the slot-labelled primary if it's itself
    // non-modifier, else the first non-modifier; hold everything else.
    ...
}

private static boolean isModifierKey(String token) {
    return token != null && BindingModifier.isSupportedKeyboardModifier("Keyboard", token);
}
```

`isModifierKey` is the identity check — it asks whether *this specific key token* is one of the
six supported Ctrl/Shift/Alt variants, regardless of which XML element it came from. The class
doc states the rationale directly:

> "Frontier's data model is positional: the game evaluates a chord as an unordered set of held
> keys, and the `.binds` format happily parks an action key (e.g. `Key_Y`) in a modifier slot
> and a modifier (e.g. `Key_LeftControl`) in the primary slot. Honouring those labels makes us
> hold the action key for the whole chord ... and only tap the modifier. We therefore pool every
> key, hold the ones that are actual Ctrl/Shift/Alt modifiers, and tap the single non-modifier
> trigger last."

Two edge cases get their own status and are logged, not silently mis-executed:
- `NO_TRIGGER` — every key in the chord is a modifier (no key to tap at all); `warnAndCheckExecutable()` logs a warning and skips execution entirely.
- `AMBIGUOUS` — two or more non-modifier keys in one chord; logged as a warning, with the slot-labelled primary preferred as the trigger if it's itself non-modifier.

**Verdict: confirmed, built exactly as described.** `KeyBindingExecutorTest.java` (new, 88
lines) exercises this directly.

## 2. `BindingConflictScanner` — exact-chord matching, NOT subset suppression

Read in full (`BindingConflictScanner.java`, 153 lines, new file). The class doc is explicit
about what model it implements and, critically, what model it *rejects*:

```java
/**
 * Detects keyboard binding conflicts using Elite Dangerous's input-matching model.
 * <p>
 * ED matches a binding by its <strong>exact</strong> chord — the main key plus exactly its
 * modifier set. Holding extra modifiers does NOT trigger a binding that has fewer of them: bare
 * Key_6 (galaxy map) and Ctrl+Alt+Key_6 (pitch) coexist and fire distinctly. So two bindings
 * conflict only when they share the identical key-set, within the same active context.
 * <p>
 * (An earlier model treated a bare key as a subset that "swallowed" modified chords on that
 * key. In-game testing disproved it — the failure that suggested it was a stale-bindings state,
 * where ED had not re-read the .binds, not a real conflict.)
 */
```

The algorithm (`scanKeysets`, lines 57-79) is a single equality check per pair:

```java
if (!ksA.equals(ksB)) {
    continue; // ED matches the exact chord; only identical chords clash
}
if (BindingConflictRules.isSafeOverlap(a, b)) {
    continue; // different vehicle state or a sub-mode overlay → never co-fire
}
conflicts.add(new Conflict(a, b, BindingConflictRules.describe(a, b)));
```

`Set<String>.equals()` is exact-match only — there is no subset/superset comparison anywhere in
this file. This is corroborated by two of the eleven tests in `BindingConflictScannerTest.java`,
written specifically to assert the *absence* of subset-suppression behavior:

```java
@Test
void bareKeyAndModifiedChordOnSameKeyDoNotConflict() {
    // The corrected model: bare Key_Y and Ctrl+Shift+Alt+Y are distinct chords, both fire.
    ...
    assertTrue(conflicts.isEmpty());
}

@Test
void subsetModifiersDoNotConflict() {
    // Ctrl+Y vs Ctrl+Shift+Y: different exact chords, no conflict.
    ...
    assertTrue(conflicts.isEmpty());
}
```

**This is the opposite of pitfall #2 in `BindForge_Binding_Rules_Reference.md`.** Krondor
apparently built and tested a subset-suppression model first, tested it in-game, found it didn't
hold up, and shipped the exact-chord model instead with an explanatory comment recording why.
Whether the subset-suppression pitfall we documented yesterday is simply wrong, or describes a
narrower/different real condition Krondor's test didn't reproduce, is now an open question — see
the bottom line.

**Context representation:** still string-based action-name heuristics, delegated to
`BindingConflictRules.isSafeOverlap()` — unchanged in *kind* from before this commit, only
broadened. `BindingConflictRules.isSubStateModeAction()` changed from prefix-matching individual
camera action names to a single substring check:

```java
// BindingConflictRules.java, diff
- return action.startsWith("FreeCam") || action.startsWith("MoveFreeCam")
-         || action.startsWith("CamTranslate") || action.startsWith("CamYaw")
-         || action.startsWith("CamZoom") || action.startsWith("FixCamera")
-         || action.startsWith("Vanity") || action.startsWith("PhotoCamera")
+ // WHY: "Cam" is matched as a substring (not a prefix) on purpose - it covers every camera
+ // action family at once... No non-camera ED action name contains "Cam".
+ return action.contains("Cam")
+         || action.startsWith("Vanity")
          || action.startsWith("MovePlacement") || action.startsWith("Placement")
          || action.startsWith("GalnetAudio")
+         || action.startsWith("MultiCrew") || action.startsWith("Store")
          || action.startsWith("ExplorationFSS") || action.startsWith("ExplorationSAA");
```
No new explicit context/enum model was introduced — vehicle state (`vehicleStateOf`, unchanged:
`_Buggy` suffix / `Humanoid` substring / else "ship") and sub-mode overlay are both still
inferred from the action-name string itself, not from any parsed game-state signal.

**Surfacing — both, not just one:**
- **Detected and surfaced to the user:** the BindForge table's action-name cells (red/green,
  §4 below) and the assign dialog's conflict label + live keyboard widget (§3 below) consume
  `BindingConflictScanner` directly and show results live in the UI.
- **Also still logged/voice-announced:** `BindingsMonitor.checkForConflictsAndPersist()` now
  delegates detection to `BindingConflictScanner.scan(...)` instead of its own old
  `normalizeCombo` logic, but narrows *which* conflicts get persisted/voice-announced to ones
  touching an "app-controlled action" (`Bindings.GameCommand`):
  ```java
  // BindingsMonitor.java, diff
  for (BindingConflictScanner.Conflict c : detectConflicts()) {
      // Announce only conflicts that touch an app-controlled command, so voice alerts stay
      // meaningful and do not flood on unrelated vanilla-vs-vanilla overlaps. The UI surfaces
      // the full set live.
      if (!APP_CONTROLLED_ACTIONS.contains(c.actionA())
              && !APP_CONTROLLED_ACTIONS.contains(c.actionB()))
          continue;
      ...
  }
  ```
  Confirmed by reading `BindForgeTabPanel.computeConflictedBindings()` (§4): it calls
  `BindingConflictScanner.scan(bindings)` on the *full* effective-bindings map, unfiltered — so
  the table really does show every conflict, while the voice/log path is intentionally narrower.

## 3. `KeyboardAvailabilityView` — new widget, layered on top of (not replacing) `KeyboardKeyAvailabilityService`

Read in full (`app/src/main/java/elite/intel/ui/widget/KeyboardAvailabilityView.java`, 193
lines, new file). It is a `JPanel` that draws a full QWERTY layout (`buildRows()`, lines 76-109)
and colors each main key live:

```java
// KeyboardAvailabilityView.java:185-192
private Color statusColor(String token) {
    if (!EliteKeyboardKeys.isAssignable(token)) {
        return HUD_COLOR_ROLE_DISABLED;
    }
    boolean conflicts = BindingConflictScanner.candidateConflict(
            bindingId, token, heldModifiers, existingBindings) != null;
    return conflicts ? HUD_COLOR_ROLE_DANGER : HUD_COLOR_ROLE_SUCCESS;
}
```

`setHeldModifiers(...)` is called externally whenever the held-modifier set changes, recoloring
every key; `setCurrentKey(...)` marks the binding's existing key as highlighted (not "free").
Modifier keys themselves highlight while held (`refresh()`, lines 177-181).

**Relationship to `KeyboardKeyAvailabilityService`: layered, not replaced.** Read
`AssignKeyboardBindingDialog.java`'s diff: the constructor still takes the existing
`KeyboardKeyAvailabilityService availabilityService` parameter unchanged, and
`isSelectedCombinationOccupied()` (the existing exact-occupancy/"already in use" check) is still
present and still gates Save. What's new is a *second*, independent gate:

```java
// AssignKeyboardBindingDialog.java, diff
private void saveSelection() {
    boolean blockingConflict = selectedConflict() != null;
    if (!isChanged() || !isValidKey() || isSelectedCombinationOccupied() || blockingConflict) {
        return;
    }
    ...
}

private BindingConflictScanner.CandidateConflict selectedConflict() {
    if (cleared || selectedKey == null || !isValidKey()) return null;
    List<String> modifierKeys = selectedModifiers.stream().map(BindingModifier::key).toList();
    return BindingConflictScanner.candidateConflict(bindingId, selectedKey, modifierKeys, existingBindings);
}
```

`updateConflictLabel()` names the colliding binding (`bindings.assign.conflict={0} ...` —
new i18n key, §6) and `updateSaveState()` disables Save when a conflict is found, distinct from
(and in addition to) the existing "already in use" exact-occupancy message.

A dialog-wide `KeyEventDispatcher` (`installModifierTracker()`) tracks modifier presses for the
whole time the dialog is open — not just while the capture field is armed — purely to drive the
keyboard widget's live coloring; it never consumes events, so it doesn't interfere with the
existing capture field from yesterday's keyboard-capture sync.

`KeyboardKeyAvailabilityService` itself also changed in this commit, made context-aware to match
the new scanner's logic (so its existing exact-occupancy check stops false-flagging
mutually-exclusive-context overlaps):

```java
// KeyboardKeyAvailabilityService.java, diff
// A binding never conflicts with itself (either slot).
if (assignment.bindingId().equals(bindingId)) {
    continue;
}
// The same chord in a mutually-exclusive context (ship / SRV / on-foot / camera /
// FSS ...) does NOT collide: Elite only evaluates one context at a time...
if (BindingConflictRules.isSafeOverlap(bindingId, assignment.bindingId())) {
    continue;
}
return true;
```

**Verdict:** `KeyboardAvailabilityView` is a new, additive visual layer consuming the new
`BindingConflictScanner`; `KeyboardKeyAvailabilityService` remains the save-time exact-occupancy
gate (now context-aware) and was not removed or superseded.

## 4. File-by-file summary of the remaining changed files

| File | Actual change |
|---|---|
| `BindingConflictRules.java` | `isSubStateModeAction()` changed from per-prefix camera-action matching to a single `action.contains("Cam")` substring check (covers every camera-axis/button family at once); added `MultiCrew`/`Store` to the safe-overlap exemption list. No behavior change to `isSafeOverlap`'s vehicle-state logic. |
| `BindingsMonitor.java` | `checkForConflictsAndPersist()` rewritten to delegate detection to `BindingConflictScanner.scan(...)` instead of its own old `normalizeCombo`/`byCombo` inversion logic (which is deleted). Added a new `APP_CONTROLLED_ACTIONS` set (derived from `Bindings.GameCommand`) that narrows which detected conflicts get persisted/voice-announced — see §2. |
| `KeyboardKeyAvailabilityService.java` | `isKeyOccupiedByOtherSlot`'s self-conflict exemption broadened from "same binding + same slot type" to "same binding, either slot"; added a `BindingConflictRules.isSafeOverlap(...)` check so mutually-exclusive-context chords no longer false-flag as occupied. No change to its public method signatures from yesterday's sync. |
| `SafeKeyboardKeys.java` | Split `BASE_KEYS` into `LAYOUT_STABLE_KEYS` (unchanged set) plus a new `LAYOUT_VARIABLE_KEYS` list (`Q W A Z M Y` plus all ASCII punctuation) that's now *included* in the auto-assign pool by default, with a one-line, explicitly-commented path to drop it and restore the old strict guard. This is a deliberate relaxation, not a bug fix — the commit message states testers confirmed these keys work fine in practice. |
| `KeyCaptureMapper.java` | Linux/X11 keysym resolution (`vkToKeysym`) fix: digits and several ASCII punctuation VK codes are now explicitly scan-resolved; the catch-all default case changed from "return the VK unchanged" to "only do that for Latin-1 accented characters (0x00A1–0x00FF), else return 0 (fall through to the VK table)" — fixes function keys/arrows/numpad colliding with ASCII keysyms that share the same numeric value. Linux-specific; does not affect Windows. |
| `AssignKeyboardBindingDialog.java` | Covered in §3 — added the live keyboard widget, the dialog-wide modifier tracker, and the conflict label/save-gate. |
| `BindForgeTabPanel.java` | Added `conflictedBindings` field + `computeConflictedBindings()` (calls `BindingConflictScanner.scan()` unfiltered, recomputed every `initData()` load) to drive the table's red/green coloring (§2, §6). Also added a post-Apply reminder (dialog text + spoken line) that Elite only re-reads `.binds` when its in-game Controls screen is opened — unrelated to conflict detection but bundled in this commit. Passes `effectiveBindings(currentSlots)` into the assign dialog's new constructor parameter. |

(`BindingSlotCellRenderer.java` and `BindingsGroupTableFactory.java` — covered directly in §6.)

## 5. AXIS/STANDALONE SETTING scope — confirmed unchanged

```
git show dea8365b -- app/src/main/java/elite/intel/ai/hands/KeyBindingsParser.java
# (no output — file untouched in this commit)

git show dea8365b | grep -i "axis|standalone|deadzone|inverted"
# only match: "setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));" — a Swing constant, unrelated
```

`KeyBindingsParser.java` — the file that decides which `.binds` XML shapes even get modeled —
was not touched at all in this commit. There is no AXIS, STANDALONE-setting, `<Binding>`,
`<Inverted>`, or `<Deadzone>` handling anywhere in this diff.

**Verdict: confirmed unchanged — still BUTTON-only, keyboard-only**, exactly as documented in
yesterday's full-state audit.

## 6. Conflict UI — now exists, confirmed three separate surfaces

This supersedes the "voice/log-only" finding for this specific (exact-chord) conflict type:

1. **BindForge table row coloring** — `BindForgeTabPanel.computeConflictedBindings()` recomputes
   a `Set<String> conflictedBindings` on every `initData()` load; `BindingSlotCellRenderer`
   (constructor now takes a `Predicate<String> hasConflict`) tints column 0 (the action name)
   red when the binding participates in a conflict, green otherwise:
   ```java
   // BindingSlotCellRenderer.java, diff
   if (column == 0) {
       label.setForeground(statusColor(String.valueOf(value)));
   }
   ...
   private Color statusColor(String bindingId) {
       boolean conflicted = hasConflict != null && hasConflict.test(bindingId);
       return conflicted ? HUD_COLOR_ROLE_DANGER : HUD_COLOR_ROLE_SUCCESS;
   }
   ```
   The commit's own "Bug fixes found during review" section notes this initially didn't work —
   the predicate was originally a bound method reference (`conflictedBindings::contains`) pinned
   to the field's *initial* empty value; it's now a lambda that reads the field live each call.
2. **Assign dialog conflict label** — names the colliding binding and blocks Save (§3).
3. **Live keyboard widget coloring** — green/red/grey per key, live as modifiers are held (§3).

New i18n keys confirm the UI text: `bindings.assign.conflict={0} ...`,
`bindings.assign.keyboard.hint=Hold Ctrl/Shift/Alt to see available keys...`, and (separately)
`speech.bindingConflicts` was reworded to point users at "the highlighted bindings in the app."

**Verdict: conflict UI exists now** — three live, visual surfaces, all sourced from
`BindingConflictScanner`. The previously-documented voice/log-only path
(`BindingsMonitor`/`KeyBindCheck`) still exists in parallel but is intentionally narrower-scoped
(app-controlled actions only), not replaced.

## Bottom line

**Confirmed built, matching the commit description exactly:** identity-based chord execution
(item 1) and a full conflict-detection UI (items 3, 6) are real and working. The previously
"voice/log-only" finding is now only true for the announcement *path*, not for conflict
*visibility* overall.

**Confirmed NOT built, and the reason matters:** the subset-key-set suppression detection we
documented as pitfall #2 in `BindForge_Binding_Rules_Reference.md` was not implemented — Krondor
apparently tried that exact model, tested it in-game, and shipped code (plus two regression
tests) explicitly asserting subsets *don't* conflict, attributing the original symptom to a
stale `.binds` reload instead. **Before touching the planning docs again, this needs a decision:
either re-verify pitfall #2 ourselves against a real game session (was Krondor's test actually
equivalent to the scenario we documented?), or correct/retract that pitfall in the reference doc
if his finding holds up.** Treating it as still-open and unaddressed, without addressing this
direct contradiction, would leave the docs internally inconsistent with the code Krondor just
shipped and tested.

**Unchanged, confirmed directly from the diff:** scope is still BUTTON-only, keyboard-only — no
AXIS or STANDALONE-setting handling was touched.
