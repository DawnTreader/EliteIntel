# BindForge Tab Split — Implementation Report — 2026-06-24

Implements the split confirmed safe by the read-only audit on file at
[`2026-06-24_BindForge_TabStructure_Audit.md`](2026-06-24_BindForge_TabStructure_Audit.md):
BIND FORGE now has two inner sub-tabs — "Binding Profile" (today's screen, unchanged) and
"Binding Management" (new, empty placeholder for a future backup/restore feature).

## What got extracted/moved

- **`BindingProfilePanel`** (new, `elite.intel.ui.screen.bindforge` package) — every field and
  method that used to live in `BindForgeTabPanel` moved here verbatim: `loader`, `parser`,
  `monitor`, `playerSession`, `availabilityService`, `bindingsWriter`, `slotFormatter`,
  `selectionController`, `tableFactory`, `workingCopyRepo`, `applyService`, `autoAssigner`,
  `profileField`, `filePathField`, `bindingsDirField`, `usedBindingsPanel`,
  `missingBindingsPanel`, `applyButton`, `revertButton`, `fixAllButton`, the inner Used/Missing
  `AppTheme.makeCompactTabs()` strip, the `BindingsUpdatedEvent` subscriber, `initData()`,
  `hasUnappliedChanges()`, `promptCloseWithDraft()`, `performApply()`, `fixAllMissing()`, and
  every private helper underneath. No logic changed — this was a pure move, confirmed by the
  build/test results below.
- **`BindForgeTabPanel`** (rewritten) — reduced from 861 lines of UI/binding logic to a 50-line
  outer shell. It now only builds an `AppTheme.makeSectionTabs()` `JTabbedPane` (the same
  `Level.SECTION` pattern `ActionsTabPanel`/`SettingsTabPanel` use) with the two sub-tabs, and
  delegates `initData()` / `promptCloseWithDraft()` / `dispose()` straight through to
  `BindingProfilePanel`.
- **`BindingManagementPanel`** (new) — empty placeholder: a bordered `JPanel` with a centered
  "Coming soon" label (`bindForge.bindingManagement.comingSoon`). No backup/restore logic, as
  scoped — that's separate future work.

Border convention followed exactly what the rest of the codebase already does for this
two-tier shape (confirmed via `ActionsTabPanel`/`SettingsTabPanel`/`CommandCatalogTablePanel`):
the outer shell uses `AppTheme.hudScreenBorder()`, the inner sub-tab content
(`BindingProfilePanel`, `BindingManagementPanel`) uses `AppTheme.hudSubtabContentBorder()` —
which `BindForgeTabPanel` was, by coincidence, already using before this change.

## New file list

```
app/src/main/java/elite/intel/ui/screen/bindforge/BindingProfilePanel.java   (new, 871 lines)
app/src/main/java/elite/intel/ui/screen/bindforge/BindingManagementPanel.java (new, 30 lines)
app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java               (rewritten, 861 -> 50 lines)
```

`elite.intel.ui.screen.bindforge` mirrors the existing `elite.intel.ui.screen.settings`
subpackage convention `SettingsTabPanel` already uses for its own sub-panels.

## AppView — confirmed zero changes

The audit predicted `AppView` would need no changes since it only ever touches
`BindForgeTabPanel` through its own field and four calls (constructor, `promptCloseWithDraft()`,
`initData()`, `dispose()`) plus `addTab(...)`. Confirmed directly:

```
git diff --stat 826e79db 57dcab6a -- app/src/main/java/elite/intel/ui/screen/AppView.java
```

Produced **no output** — `AppView.java` is untouched by this commit. The prediction held exactly;
nothing deviated from the audit.

## i18n

Added to all seven locale files (`gui.properties`, `gui_de`, `gui_es`, `gui_fr`, `gui_pt`,
`gui_ru`, `gui_uk`), following the existing `<screen>.tab.<name>` convention
(`actions.tab.commands`, `settings.tab.aiServices`):

- `bindForge.tab.bindingProfile` = "Binding Profile" (translated per locale)
- `bindForge.tab.bindingManagement` = "Binding Management" (translated per locale)
- `bindForge.bindingManagement.comingSoon` = "Coming soon" (translated per locale)

Also added the `bindings.section.profile` key to `gui_es.properties`, which the audit flagged as
already missing it before this change (`= "Perfil de bindings"`) — same gap, fixed while in the
file.

## Build/test results

```
./gradlew compileJava compileTestJava
```
**BUILD SUCCESSFUL** — no errors.

```
./gradlew test
```
**BUILD SUCCESSFUL** — full default suite (`app:test`) passed, nothing touching BindForge broke.

## Commit / push

Code change committed separately from this report (docs commits stay separate, per convention):

```
57dcab6a feat(bindforge): split BIND FORGE into Binding Profile / Binding Management sub-tabs
 10 files changed, 948 insertions(+), 836 deletions(-)
```

Confirmed push target first:
```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Pushed:
```
826e79db..57dcab6a  bindforge-gamemode -> bindforge-gamemode
```
to `https://github.com/DawnTreader/EliteIntel.git` only — `git push origin ...` was never run.

`origin` checked both before and after the push via `git ls-remote origin bindforge-gamemode`:
**returned nothing both times** — `origin` has no knowledge of `bindforge-gamemode` at all,
before or after. `mine/bindforge-gamemode` matches local HEAD exactly (`57dcab6a`) after the
push.

## End state

- Current branch: `bindforge-gamemode` @ `57dcab6a`
- BIND FORGE tab now shows "Binding Profile" / "Binding Management" sub-tabs; Binding Profile's
  content and behavior are byte-for-byte the same as before, just relocated
- Binding Management is an empty, wired-in placeholder — ready for the backup/restore feature as
  a separate task
- `AppView`: confirmed untouched, exactly as the audit predicted
- Build and full test suite: both green
- `origin`: completely unchanged throughout
