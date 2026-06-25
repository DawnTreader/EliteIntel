# BindForge Backup Creation — Implementation Report — 2026-06-24

Implements the first real slice of the backup feature inside the "Binding Management" sub-tab
added in `57dcab6a`, per the settled design in `BindForge_Punch_List.md` Section G. Scope for
this change: **backup creation and listing only** — no restore yet, that's separate future work.

## Existing discovery logic reused vs. new

**Reused, not re-derived:**
- The bindings directory itself: `PlayerSession.getInstance().getBindingsDir()` — the same call
  `BindingsLoader` and `BindingProfilePanel` already use.
- `.binds` file discovery: `BindingsLoader` already had an inline `Files.list(bindingsDir)
  .filter(p -> p.toString().endsWith(".binds"))` idiom (used in `getLatestBindsFile()`'s
  fallback branch). Extracted this into a new public method,
  **`BindingsLoader.listAllBindsFiles(Path)`**, and changed `getLatestBindsFile()`'s fallback to
  call it instead of duplicating the filter a second time — net DRY improvement, zero behavior
  change (confirmed by the existing `BindingsApplyServiceTest`/build staying green, and by every
  other `BindingsLoader` caller being untouched).
- `StartPreset.*.start` location: `BindingsLoader.findActivePresetName()` already located this
  file internally (it reads the preset name out of it) via an inline `Files.list(bindingsDir)
  .filter(name.startsWith("StartPreset.") && name.endsWith(".start")).findFirst()`. Extracted
  this into a new public method, **`BindingsLoader.findStartPresetFile(Path)`**, and had
  `findActivePresetName()` call it instead of inlining the same filter — again, pure extract,
  same try/catch wrapping, no behavior change.

**New:**
- `PlayerBackupService` (the actual backup sweep/list logic) and `AppPaths.getPlayerBackupsDir()`
  (location resolution) — nothing existing did this; see below.

## New file list

```
app/src/main/java/elite/intel/ai/hands/PlayerBackupService.java        (new)
app/src/test/java/elite/intel/ai/hands/PlayerBackupServiceTest.java    (new)
app/src/main/java/elite/intel/ai/hands/BindingsLoader.java             (extended, behavior-preserving)
app/src/main/java/elite/intel/util/AppPaths.java                      (extended: + getPlayerBackupsDir())
app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java         (initData() now also refreshes Binding Management)
app/src/main/java/elite/intel/ui/screen/bindforge/BindingManagementPanel.java (rewritten: placeholder -> real UI)
app/src/main/resources/i18n/gui*.properties (all 7 locales)           (new bindForge.bindingManagement.* keys)
```

## `PlayerBackupService` (singleton, not DI — per Section B/G)

Same `private static volatile instance` + double-checked-locking `getInstance()` pattern as
`BindingsMonitor`/`KeyBindingManager`/`BindingConflictManager` elsewhere in this codebase — not
constructor-injected, matching the explicit Krondor-confirmed constraint in Punch List Section B.

- `createBackup()` — resolves `bindingsDir`, collects every `.binds` file
  (`bindingsLoader.listAllBindsFiles`) plus the `StartPreset.*.start` file
  (`bindingsLoader.findStartPresetFile`), creates a new timestamped folder
  (`yyyy-MM-dd_HH-mm-ss`, with a `-1`/`-2`/... suffix on same-second collision) under
  `playerbackups`, and copies every file in with its real filename intact via
  `Files.copy(..., COPY_ATTRIBUTES)`. `DeviceMappings.xml`/`.buttonMap` are never touched — only
  `.binds` and `StartPreset.*.start` are matched.
- `listBackups()` — lists the timestamped subfolders under `playerbackups`, newest-first
  (the `yyyy-MM-dd_HH-mm-ss` folder name sorts correctly as a plain string), each exposed as a
  `PlayerBackup(Path folder, String timestamp, List<String> fileNames)` record — flat list, no
  preset grouping, per the settled design.
- A package-private `createBackup(Path bindingsDir)` overload is the actual implementation (the
  public no-arg version just resolves `bindingsDir` and delegates) — this is the same test-seam
  shape `BindingsBackupService.createBackup(file, targetDirectory)` already uses elsewhere in
  this codebase, letting the unit test exercise real file I/O against a `@TempDir` without
  touching `PlayerSession`/the database.

## `AppPaths.getPlayerBackupsDir()`

```java
public static Path getPlayerBackupsDir() throws IOException {
    Path dir = getAppDataBase().resolve("elite-intel/playerbackups");
    Files.createDirectories(dir);
    return dir;
}
```

Uses the exact same `getAppDataBase()` (XDG_DATA_HOME-aware on Linux/macOS, `LOCALAPPDATA` on
Windows) that `getBindingsBackupDir()`, `getDatabasePath()`, etc. already use — `playerbackups`
sits as a new sibling alongside `bindings/`, `db/`, `custom-commands/`, exactly as the Punch
List's literal example (`%LOCALAPPDATA%\elite-intel\playerbackups\`) specifies. Confirmed
deliberately separate from `getBindingsBackupDir()` (`elite-intel/bindings/backups/`) — that
method is untouched, still used only by `BindingsApplyService`'s internal per-Apply safety net.

**Confirmed on this machine:** `LOCALAPPDATA=C:\Users\AlanRTonn\AppData\Local`, and
`C:\Users\AlanRTonn\AppData\Local\elite-intel\` already exists with `bindings\`,
`custom-commands\`, and `db\` siblings (from prior real use of the app) — `playerbackups` will
land at `C:\Users\AlanRTonn\AppData\Local\elite-intel\playerbackups\` the first time a backup is
created or the list is loaded. It doesn't exist yet on this machine since this change hasn't
been exercised through the live UI yet, only through the build/unit tests below.

## `BindingManagementPanel` — placeholder replaced with real UI

Follows the same conventions as `BindingProfilePanel`/`ActionsTabPanel`: `AppTheme
.hudSubtabContentBorder()`, a `HudSection` (FLAT variant) wrapping a `HudTable`-styled,
non-editable `JTable` in a `HudTable.dataPlaneScrollPane`, and a `HudFooter.build(...)` footer
holding the "Backup Now" button (mirrors `SettingsTabPanel`'s single-trailing-button footer
shape). Table columns: **Created** (the timestamp folder name) and **Files** (comma-joined file
list) — simplest layout that satisfies "timestamp + file list per row," no restore actions yet.
`BindForgeTabPanel.initData()` now also calls `bindingManagementPanel.initData()` so the backup
list loads through the same lifecycle `AppView.initData()` already drives.

## i18n

Added under the existing `bindForge.bindingManagement.*` prefix, in all seven locale files
(`gui.properties`, `gui_de`, `gui_es`, `gui_fr`, `gui_pt`, `gui_ru`, `gui_uk`):

- `bindForge.bindingManagement.section.backups` ("Player Backups")
- `bindForge.bindingManagement.button.backupNow` ("Backup Now")
- `bindForge.bindingManagement.column.created` ("Created")
- `bindForge.bindingManagement.column.files` ("Files")
- `bindForge.bindingManagement.backup.dialogTitle` ("Backup Failed")
- `bindForge.bindingManagement.backup.error` ("Could not create backup: {0}")

Removed the now-dead `bindForge.bindingManagement.comingSoon` key from all seven files (no longer
referenced anywhere now that the placeholder is gone).

## Build/test results

```
./gradlew compileJava compileTestJava
```
**BUILD SUCCESSFUL** — no errors.

```
./gradlew test
```
**BUILD SUCCESSFUL** — full default suite passed. New `PlayerBackupServiceTest` (3 tests, all
green, confirmed via `TEST-elite.intel.ai.hands.PlayerBackupServiceTest.xml`:
`tests="3" skipped="0" failures="0" errors="0"`):
- sweeps `.binds` files + `StartPreset.4.start` into a timestamped folder, real filenames intact,
  and confirms `DeviceMappings.xml` is **not** swept (out of scope)
- a same-second second backup gets a collision-safe `-1` suffix
- `listBackups()` returns newest-first with each backup's file list

## Commit / push

```
ef12e9bb feat(bindforge): add player backup creation and listing to Binding Management
 13 files changed, 400 insertions(+), 23 deletions(-)
 create mode 100644 app/src/main/java/elite/intel/ai/hands/PlayerBackupService.java
 create mode 100644 app/src/test/java/elite/intel/ai/hands/PlayerBackupServiceTest.java
```

Confirmed push target first:
```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Pushed:
```
7628b18c..ef12e9bb  bindforge-gamemode -> bindforge-gamemode
```
to `https://github.com/DawnTreader/EliteIntel.git` only — `git push origin ...` was never run.

`origin` checked both before and after via `git ls-remote origin bindforge-gamemode`: **returned
nothing both times** — no knowledge of `bindforge-gamemode` at all, before or after.
`mine/bindforge-gamemode` matches local HEAD exactly (`ef12e9bb`) after the push.

## End state

- Current branch: `bindforge-gamemode` @ `ef12e9bb`
- "Binding Management" now has a working "Backup Now" button and a flat, newest-first list of
  existing backups; "Binding Profile" is untouched
- `playerbackups` location confirmed correct and isolated from the existing per-Apply backup
  mechanism, on this machine and via the XDG/LOCALAPPDATA-aware `AppPaths` resolution
- Restore is explicitly out of scope here — no restore buttons/actions exist yet
- Build and full test suite: both green, including 3 new focused unit tests
- `origin`: completely unchanged throughout
- Note: two report files from the prior tab-split task (`2026-06-24_BindForge_TabStructure_Audit.md`,
  `2026-06-24_sync_report_2.md`) remain untracked — not committed as part of this change since
  this task's instructions didn't ask for that this time
