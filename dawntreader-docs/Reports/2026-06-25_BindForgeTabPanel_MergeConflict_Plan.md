# BindForgeTabPanel Merge Conflict — Investigation & Plan
**Date:** 2026-06-25  
**Branch:** `bindforge-gamemode` ← `V1.1` (commit `25a8b710`)  
**Operator:** Claude Code (claude-sonnet-4-6)

---

## 1. Scope of the conflict

Only one file failed to auto-merge: `BindForgeTabPanel.java`. All 23 other files modified in
`25a8b710` — including the new `BindingConflictPopup`, `ReservedKeyChords`, the reworked
`BindingsGroupTableFactory`, the updated `AssignKeyboardBindingDialog`, `BindingConflictRules`,
`BindingsWriter`, `SafeKeyboardKeys`, `KeyboardAvailabilityView`, all i18n files, and all new
tests — **auto-merge cleanly**. None of the other 19 commits in the 20-commit V1.1 batch touch
anything our branch changed.

The conflict is purely structural: V1.1 modified the old 1,026-line monolith; our branch replaced
it with a 56-line shell. Git cannot reconcile those.

---

## 2. What `25a8b710` added to `BindForgeTabPanel.java`

The commit message describes six features. Here is every new piece specifically in
`BindForgeTabPanel`, catalogued by category:

### 2a. Data model upgrade — conflict map

| Old | New |
|-----|-----|
| `Set<String> conflictedBindings` | `Map<String, Set<String>> conflictsByBinding` |
| `computeConflictedBindings()` → `Set<String>` | `computeConflicts()` → `Map<String, Set<String>>` |

The old approach knew only whether a binding was conflicted; the new one also knows *which partner
ids* it shares a chord with. `computeConflicts()` builds a bidirectional map: for each
`BindingConflictScanner.Conflict(actionA, actionB)` pair it inserts `actionA → {actionB}` and
`actionB → {actionA}`, accumulating via `TreeSet` so partners are sorted.

### 2b. Hover callout — `buildConflictPopupContent(String bindingId)`

New private method returning a `JComponent` (or `null`). Builds a themed red `HudPanel` card that
names the shared chord (`conflictChordText()`) and lists each partner with `StringUtls.humanizeBindingName()`.
This is passed as a `Function<String, JComponent>` method reference to `BindingsGroupTableFactory`
as its new 5th constructor arg. The factory's auto-merged `updateConflictPopup()` calls it on
mouse-move to show/hide the persistent `BindingConflictPopup` window.

Two supporting private methods come with it:

- **`conflictChordText(String bindingId)`** — formats the chord (e.g. "Left Ctrl + A") using
  `slotFormatter.formatChord()` on whichever slot is keyboard-usable.
- **`conflictingSlot(ReadOnlyBindingSlots)`** — picks primary over secondary for the above.

### 2c. "Show conflicts only" filter — `JCheckBox conflictsOnlyCheck`

New field. Built in `buildFooter()` using `makeCheckBox()` and prepended to the footer button list
(left of `fixAllButton`). Its `ActionListener` calls `renderBindingTables()`.

New helper: **`filterConflictsOnly(List<String> bindingIds)`** — a no-op pass-through unless the
checkbox is selected, in which case it returns only ids present in `conflictsByBinding.keySet()`.

### 2d. `renderBindingTables()` extraction

The Used/Missing table rendering that was inline in `initData()` is extracted into
`renderBindingTables()`. It's called from `initData()` after loading, and also directly from the
filter checkbox listener. It calls `tableFactory.hideConflictPopup()` first (so the old callout
doesn't float while tables are being rebuilt). It applies `filterConflictsOnly()` to both lists but
keeps unfiltered counts for the tab titles and the `fixAllButton` enable state.

### 2e. Dialog changes — `openAssignKeyboardBindingDialog()`

Two additions:
1. `tableFactory.hideConflictPopup()` called before opening the modal (so the hover callout doesn't
   float over it — no `mouseExited` fires on a row click).
2. Two new args passed to `AssignKeyboardBindingDialog`: `conflictsByBinding.get(bindingId)` and
   `conflictChordText(bindingId)`. The dialog uses these to render a static red banner inside itself
   naming the pre-existing conflict, since the hover callout is unreachable while the modal is open.

### 2f. Error path reset

In `initData()`'s catch block, `conflictedBindings = Set.of()` → `conflictsByBinding = Map.of()`.

---

## 3. Where each piece belongs in our split structure

**Short answer: 100% goes into `BindingProfilePanel`. Nothing goes into `BindingManagementPanel`.**

`BindingManagementPanel` is a self-contained backup/restore UI with its own table, footer, and
service calls. It has no binding tables, no conflict data, no `BindingsGroupTableFactory`, and no
`AssignKeyboardBindingDialog`. It is entirely unaffected by `25a8b710`.

Every feature in `25a8b710`'s `BindForgeTabPanel` changes belongs to the binding-editing panel:
conflict display is driven by the same `BindingConflictScanner` data that was already in
`BindingProfilePanel`, the filter checkbox filters the Used/Missing tables that live in
`BindingProfilePanel`, and the dialog integration is in `openAssignKeyboardBindingDialog()` which
is also in `BindingProfilePanel`.

---

## 4. Overlap with existing `BindingProfilePanel` code

`BindingProfilePanel` was extracted "unchanged" from the old monolith (commit `57dcab6a`). That
means it has the **pre-`25a8b710` baseline** of the conflict code:

| BindingProfilePanel (now) | Target after integration |
|---------------------------|--------------------------|
| `Set<String> conflictedBindings` | `Map<String, Set<String>> conflictsByBinding` |
| `computeConflictedBindings()` returning `Set<String>` | `computeConflicts()` returning `Map<String, Set<String>>` |
| `tableFactory = new BindingsGroupTableFactory(... 4 args ...)` | `... 5 args: add this::buildConflictPopupContent` |
| No `conflictsOnlyCheck` | Add `JCheckBox conflictsOnlyCheck` |
| Inline rendering in `initData()` | Extract to `renderBindingTables()`; `initData()` calls it |
| `openAssignKeyboardBindingDialog()` calls dialog with 7 args | Must call with 9 args (add 2 new) |

This is a clean upgrade relationship — no duplication to resolve, no conceptually competing
implementations. The existing `computeConflictedBindings()` is the exact same scanner call; the
new version just builds a richer data structure from the same result.

### Critical compile issue that must be resolved as part of this merge

`AssignKeyboardBindingDialog`'s constructor gained 2 new parameters in `25a8b710` (that file
auto-merges from V1.1). After the merge, the constructor signature is 9 params:

```java
public AssignKeyboardBindingDialog(
    JComponent parent, Path file, String bindingId, BindingSlotType slotType,
    KeyBindingsParser.ReadOnlyBindingSlot currentSlot,
    KeyboardKeyAvailabilityService availabilityService,
    Map<String, KeyBindingsParser.KeyBinding> existingBindings,
    Set<String> existingConflicts,        // NEW
    String existingConflictChord          // NEW
)
```

`BindingProfilePanel.openAssignKeyboardBindingDialog()` currently passes 7 args. After merge this
will fail to compile. The fix is part of the `openAssignKeyboardBindingDialog()` update described
above (item 11 in the plan below).

---

## 5. Files that need no manual work after the merge

All the following auto-merge from V1.1 and need no manual edits:

| File | What arrived |
|------|-------------|
| `BindingConflictPopup.java` | New widget (50 lines) — the persistent hover callout component |
| `ReservedKeyChords.java` | New class — OS-reserved chord detection (Alt+F4, Linux Ctrl+Alt+F*) |
| `BindingsGroupTableFactory.java` | 5th ctor arg, `hideConflictPopup()`, `updateConflictPopup()` |
| `AssignKeyboardBindingDialog.java` | 2 new ctor args, reserved label, blue hint banners, clear-button layout fix |
| `BindingConflictRules.java` | Context-group refactor: UI_* and construction added; radial wheels added to sub-state |
| `BindingsWriter.java` | Hold-child preserve/clear fix for press-and-hold bindings |
| `SafeKeyboardKeys.java` | Reserved chord exclusion from auto-assign pool |
| `KeyboardAvailabilityView.java` | Amber tint for reserved chords on live keyboard map |
| `gui*.properties` (all 7) | 3 new keys: `bindings.assign.reserved`, `bindings.filter.conflictsOnly`, `bindings.conflict.popup.title` |
| `BindingConflictScannerTest`, `BindingsWriterTest`, `ReservedKeyChordsTest`, `SafeKeyboardKeysTest` | New/expanded tests |
| `Bindings.java`, `GoogleVoiceProvider.java`, `EnglishInputNormalizerRules.java` | Unrelated cleanup (flagged in Krondor's commit note) |

---

## 6. Proposed resolution plan

### Step A — Resolve the git conflict (trivial)

When the merge is re-attempted, accept **ours** for `BindForgeTabPanel.java` unconditionally.
V1.1's 1,026-line monolith version is discarded. The 56-line shell is correct.

### Step B — Update `BindingProfilePanel.java` (the real work, 12 changes)

All changes stay in `BindingProfilePanel.java`. **One new import** is needed: `elite.intel.util.StringUtls`
(for `humanizeBindingName` in the popup builder). All other needed types are already imported.

1. **Field: replace** `Set<String> conflictedBindings = Set.of()` with  
   `Map<String, Set<String>> conflictsByBinding = Map.of()`

2. **Field: add** `private JCheckBox conflictsOnlyCheck;` (with the other `JButton` fields)

3. **Constructor: update** `BindingsGroupTableFactory` call from 4 args to 5:
   - Replace `id -> conflictedBindings.contains(id)` with `id -> conflictsByBinding.containsKey(id)` as arg 4  
   - Add `this::buildConflictPopupContent` as arg 5

4. **`buildFooter()`:** build `conflictsOnlyCheck` with `makeCheckBox()`, add its `ActionListener`
   calling `renderBindingTables()`, and prepend it to the footer's button list (left of `fixAllButton`)

5. **`initData()` — replace `computeConflictedBindings(...)` call** with `computeConflicts(...)`,
   assigning to `conflictsByBinding`; then **replace the inline table rendering block** with a
   single call to `renderBindingTables()`

6. **`initData()` catch block:** reset `conflictsByBinding = Map.of()`  
   (remove the old `conflictedBindings = Set.of()`)

7. **Add `renderBindingTables()`:** move the partition + two `renderGroupedTables()` calls here from
   `initData()`; add `tableFactory.hideConflictPopup()` at the top; apply `filterConflictsOnly()`
   to both lists before passing them to `groupedBindings()`

8. **Add `filterConflictsOnly(List<String> bindingIds)`:** no-op unless `conflictsOnlyCheck` is
   selected; filters to `conflictsByBinding.containsKey(id)` when active

9. **Replace `computeConflictedBindings()`** with `computeConflicts()`: same `BindingConflictScanner.scan()`
   call, now builds `Map<String, Set<String>>` using `computeIfAbsent(..., k -> new TreeSet<>())`
   and adds both directions for each conflict pair

10. **Add `buildConflictPopupContent(String bindingId)`:** returns `null` if no conflict; otherwise
    builds the red `HudPanel.Variant.FRAMED` card with title (chord name) and bullet list of partners
    via `StringUtls.humanizeBindingName()`

11. **Add `conflictChordText(String bindingId)`:** looks up `currentSlots.get(bindingId)`, calls
    `conflictingSlot()` to pick primary or secondary, formats with `slotFormatter.formatChord()`

12. **Add `conflictingSlot(ReadOnlyBindingSlots)`:** returns primary if keyboard-usable, else
    secondary if keyboard-usable, else null

13. **`openAssignKeyboardBindingDialog()`:**
    - Add `tableFactory.hideConflictPopup()` before the `AssignKeyboardBindingDialog` instantiation
    - Add 2 new args: `conflictsByBinding.get(bindingId)` and `conflictChordText(bindingId)`
    - This also fixes the compile error caused by `AssignKeyboardBindingDialog`'s new 9-arg constructor

### Step C — No changes to `BindingManagementPanel.java` or `BindForgeTabPanel.java`

The 56-line shell is correct as-is. `BindingManagementPanel` is unaffected.

---

## 7. Risk assessment

**Low risk.** The changes to `BindingProfilePanel` are additive: the underlying conflict-scanning
mechanism (`BindingConflictScanner.scan()`) is unchanged; only the data structure holding its
results is enriched. The `renderBindingTables()` extraction is a pure refactor of code already in
`initData()`. The popup infrastructure (`BindingConflictPopup`, the factory's 5th arg, the
mouse-move handler) all auto-merged and are already wired up on the factory side — they just need
the missing callback from `BindingProfilePanel` to activate.

The only non-trivial piece is `buildConflictPopupContent()` building a UI component, but its
template is directly readable from the V1.1 diff and it's self-contained (no new state, no new
threads, no new event subscriptions).
