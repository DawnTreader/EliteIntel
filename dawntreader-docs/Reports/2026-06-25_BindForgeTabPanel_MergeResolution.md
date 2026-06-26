# BindForgeTabPanel Merge Resolution Report

**Date:** 2026-06-25  
**Branch:** `bindforge-gamemode`  
**Merge commit:** `f4fa93cf`  
**Integration branch:** `origin/V1.1`

---

## Summary

Resolved the merge conflict between `bindforge-gamemode` and `origin/V1.1` caused by a structural
divergence: this branch had split the 866-line `BindForgeTabPanel` monolith into a 56-line shell
plus `BindingProfilePanel` and `BindingManagementPanel`, while V1.1 simultaneously added ~240 lines
of new binding-editor UX to that same monolith. Git could not auto-merge.

**Strategy:** accept OURS for `BindForgeTabPanel.java` (the 56-line shell stays), then forward-port
all V1.1 changes that belonged to the binding-editor into `BindingProfilePanel`.

---

## Step A — Merge re-attempted; conflict resolved

`git merge origin/V1.1` was re-run after restoring the stash. All files auto-merged except
`BindForgeTabPanel.java`. That file was resolved with:

```
git checkout --ours -- app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java
git add app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java
```

The 56-line shell is unchanged; all binding-editor logic remained in `BindingProfilePanel`.

---

## Step B — Changes applied to BindingProfilePanel.java

The plan specified 13 named changes. During execution a 14th body of work was discovered: commit
`78a8e54c` ("reworked the honk and auto-honk commands…") also extended `BindingsGroupTableFactory`
to a 6-parameter constructor with a `hasRecommendation` predicate and a recommendation system
(cyan tint for ship/SRV twin bindings). All of these were ported in the same pass.

### Import

| # | Change | Status |
|---|--------|--------|
| 1 | `import elite.intel.util.StringUtls` added | Applied |

*(Import ordering defect later corrected by code-integrity review — see below.)*

### Field changes

| # | Change | Status |
|---|--------|--------|
| 2 | `Set<String> conflictedBindings = Set.of()` replaced with `Map<String, Set<String>> conflictsByBinding = Map.of()` | Applied |
| 3 | New field `Map<String, Set<String>> recommendationsByBinding = Map.of()` | Applied |
| 4 | New field `JCheckBox conflictsOnlyCheck` | Applied |

### Constructor — BindingsGroupTableFactory call

| # | Change | Status |
|---|--------|--------|
| 5 | 4-arg call expanded to 6-arg: added `id -> recommendationsByBinding.containsKey(id)` (5th) and `this::buildRowCalloutContent` (6th) | Applied |

### buildFooter()

| # | Change | Status |
|---|--------|--------|
| 6 | `conflictsOnlyCheck` checkbox created, wired to `renderBindingTables()`, added to footer items | Applied |

### initData()

| # | Change | Status |
|---|--------|--------|
| 7 | `computeConflictedBindings()` replaced by `computeConflicts()` (new method, richer return type) + `computeRecommendations()` (new method) | Applied |
| 8 | Inline Used/Missing table rendering replaced by `renderBindingTables()` call | Applied |
| 9 | Error path reset: `conflictsByBinding = Map.of(); recommendationsByBinding = Map.of()` | Applied |

### New methods

| # | Method | Status |
|---|--------|--------|
| 10 | `computeConflicts()` — returns `Map<String, Set<String>>` keyed by each conflicting id; value is sorted set of the ids it collides with | Applied |
| 11 | `computeRecommendations()` — mirrors computeConflicts() shape for ship/SRV twin recommendations | Applied |
| 12 | `renderBindingTables()` — extracts Used/Missing table rendering from initData(); honours conflictsOnlyCheck filter; calls tableFactory.hideConflictPopup() before rebuild | Applied |
| 13 | `buildRowCalloutContent()` — dispatches to buildConflictPopupContent() (conflict outranks recommendation) | Applied |
| 14 | `buildConflictPopupContent()` — returns RED-accented callout card or null | Applied |
| 15 | `buildRecommendationPopupContent()` — returns CYAN-accented callout card or null | Applied |
| 16 | `filterConflictsOnly()` — narrows binding-id list to conflicting ids when filter checkbox is on | Applied |
| 17 | `conflictChordText()` — resolves the formatted chord (e.g. "Left Ctrl + A") for a conflicting binding | Applied |
| 18 | `conflictingSlot()` — returns the keyboard-usable slot the scanner matched on (primary before secondary) | Applied |

### openAssignKeyboardBindingDialog() changes

| # | Change | Status |
|---|--------|--------|
| 19 | `tableFactory.hideConflictPopup()` called before dialog is opened (hover callout would otherwise float over modal) | Applied |
| 20 | Two new args to `AssignKeyboardBindingDialog`: `conflictsByBinding.get(bindingId)` and `conflictChordText(bindingId)` | Applied |

---

## Step C — BindingManagementPanel and BindForgeTabPanel confirmed unchanged

- `BindingManagementPanel.java`: no changes needed; backup/restore logic is unrelated to the conflict/recommendation system.
- `BindForgeTabPanel.java`: conflict resolved by accepting OURS; the 56-line shell delegates dispose(), promptCloseWithDraft(), and initData() to both sub-panels — no changes required.

---

## Build and Test Results

```
./gradlew compileJava compileTestJava   →  BUILD SUCCESSFUL
./gradlew test                          →  BUILD SUCCESSFUL  (all tests pass)
```

No test failures, no compile errors.

---

## Code-Integrity Review Findings

Three issues were found in the merge-resolution changes and fixed directly before committing:

### Finding 1 — Import misplaced `[convention]`

**File:** `BindingProfilePanel.java:27`  
**Problem:** `import elite.intel.util.StringUtls` was appended after the `java.*` block instead of
with the other `elite.intel.*` imports.  
**Fix:** Moved to line 15, after `elite.intel.ui.widget.*`, before `javax.swing.*`.

### Finding 2 — DRY violation in popup builders `[duplication]`

**File:** `BindingProfilePanel.java`  
**Problem:** `buildConflictPopupContent()` and `buildRecommendationPopupContent()` contained ~15
identical lines of Swing layout setup (HudPanel, BoxLayout body, EmptyBorder, title JLabel styling,
bullet-list loop). CODING_STANDARD.md explicitly requires: "never duplicate logic across two places;
extract it into a shared method so a bug fix only needs to happen once."  
**Fix:** Extracted `buildCalloutCard(Color accent, String title, Set<String> partners)` as a private
helper. Both callers are now 4-line guard + delegate.

### Finding 3 — Stale class-level Javadoc `[clarity]`

**File:** `BindingProfilePanel.java:36`  
**Problem:** Javadoc still read "Extracted unchanged from the former monolithic BindForgeTabPanel…"
The class has been significantly extended by this merge resolution (conflict map, recommendations,
filter checkbox, hover callouts).  
**Fix:** Updated Javadoc to describe the current responsibilities.

### Pre-existing note (not introduced by this change set)

`BindingsGroupTableFactory.conflictPopupContent` field and its Javadoc say "for a conflicting
binding id" but the field now routes both conflict and recommendation callouts (via
`buildRowCalloutContent`). This is pre-existing V1.1 code; flagging for awareness only.

---

## Commit and Push

| Action | Result |
|--------|--------|
| Merge commit | `f4fa93cf` |
| Pushed to `mine/bindforge-gamemode` | Yes (`793edc21..f4fa93cf`) |
| `origin` (SudoKrondor) touched | **No** — `git ls-remote origin refs/heads/bindforge-gamemode` returned empty before and after |
| WIP stash popped | Yes — 7 i18n files auto-merged cleanly; AiServicesSettingsPanel and punch list restored |

---

## End State

`bindforge-gamemode` is fully merged with V1.1 (merge commit `f4fa93cf`). All V1.1 binding-editor
UX (conflict map, hover callouts, reserved keys, hold-child fix, ship/SRV twin recommendations,
conflicts-only filter) is now present in `BindingProfilePanel`. The tab-split architecture is intact.
Build and tests are green. Pre-merge WIP changes are back in the working tree.
