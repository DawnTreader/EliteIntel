# Elite Dangerous Installation Detection — Codebase Audit

**Scope:** Read-only investigation. No code written or modified.

## TL;DR

**There is no installation-discovery mechanism anywhere in this codebase — confirmed by
exhaustive grep and direct reading.** No registry probing, no Steam `libraryfolders.vdf`
parsing, no Epic manifest parsing, no `Products\*\` enumeration, nothing that distinguishes a
real/current install from a stale leftover folder, and nothing that reacts to a game update
changing the install itself. The journal and bindings folders the app already uses are **pure
hardcoded per-OS string literals**, not discovered paths, with a DB-backed manual override and a
`JFileChooser` "Browse…" picker as the only way a user ever points the app somewhere else. This
matches — almost finding-for-finding — an existing report,
`dawntreader-docs/Reports/INSTALLATION_DISCOVERY_RESEARCH.md` ("Existing Codebase Findings"
section, §1–§4), which already did this exact audit. The two points that report didn't directly
address (stale-vs-current install detection, and reacting to a game update) are also confirmed
absent below. Net: there is nothing to extend for BindForge's install-discovery problem — it has
to be built from scratch.

---

## 1. Registry / Steam VDF / Epic manifest detection

**Zero matches anywhere in `app/` for any of:** `Steam`, `steamapps`, `libraryfolders`,
`vdf`/`VDF`, `Epic`, `EpicGames`, `.item` manifests, `Advapi32`, `WinReg`,
`Preferences.userRoot`, `reg.exe`/`reg query`, or any literal `HKEY_` string.

The only repo-wide hit for "Steam" is in `distribution/installer.sh` — the **EliteIntel
installer's own** Linux shell script, which checks whether Steam itself is installed via SNAP
packaging in order to place EliteIntel's own desktop shortcut correctly. This is about
EliteIntel's installer, not about detecting an Elite Dangerous game install, and is unrelated to
this question.

The only registry-adjacent JNA/Win32 code in the app at all:

- `elite.intel.util.AppPaths.toNativePath()` — `Kernel32.GetShortPathNameW`, used to shorten
  paths with non-ASCII usernames so native STT/TTS libraries can load them. No registry access,
  no install-path relevance.
- `elite.intel.ui.support.GameWindowActivator` — uses `User32` (`EnumWindows`,
  `SetForegroundWindow`, `ShowWindow`, `GetWindowText`) to find and foreground the Elite
  Dangerous **window** by matching its title (`"elite - dangerous"` / `"elite dangerous"`
  substrings). This is window-detection, not install-path or process detection — it reveals
  nothing about where the game is installed, and it's the closest thing in the codebase to "is
  Elite Dangerous running."
- `WindowsNativeKeyInput` / `LinuxX11NativeKeyInput` — JNA for synthetic key dispatch, unrelated.

**No process-detection** (`ProcessHandle`, `tasklist`, WMI) for an `EliteDangerous64.exe`-style
executable exists anywhere either.

## 2. How the journal and bindings folders are actually found today

Both are **hardcoded per-OS literal paths**, not discovered. The two resolver methods sit
directly adjacent to each other in `elite.intel.session.PlayerSession`:

```java
// PlayerSession.java:679-690
public Path getBindingsDir() {
    return Database.withDao(PlayerDao.class, dao -> {
        String directory = trimToNull(dao.get().getBindingsDirectory());
        if (OsDetector.getOs() == OsDetector.OS.WINDOWS) {
            return directory == null ? Paths.get(System.getProperty("user.home"), "AppData", "Local", "Frontier Developments", "Elite Dangerous", "Options", "Bindings") : Paths.get(directory);
        } else if (OsDetector.getOs() == OsDetector.OS.LINUX) {
            return directory == null ? Paths.get(System.getProperty("user.home"), ".var", "app", "elite.intel.app", "ed-bindings") : Paths.get(directory);
        } else {
            return directory == null ? Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Frontier Developments", "Elite Dangerous", "Options", "Bindings") : Paths.get(directory);
        }
    });
}
```

```java
// PlayerSession.java:657-668
public Path getJournalPath() {
    return Database.withDao(PlayerDao.class, dao -> {
        String directory = trimToNull(dao.get().getJournalDirectory());
        if (OsDetector.getOs() == OsDetector.OS.WINDOWS) {
            return directory == null ? Paths.get(System.getProperty("user.home"), "Saved Games", "Frontier Developments", "Elite Dangerous") : Paths.get(directory);
        } else if (OsDetector.getOs() == OsDetector.OS.LINUX) {
            return directory == null ? Paths.get(System.getProperty("user.home"), ".var", "app", "elite.intel.app", "ed-journal") : Paths.get(directory);
        } else {
            return directory == null ? Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Frontier Developments", "Elite Dangerous") : Paths.get(directory);
        }
    });
}
```

**Key points:**

- **No path-resolution/detection layer of any kind.** The default is a single literal string
  per OS, built from `System.getProperty("user.home")` + hardcoded segments — not
  `System.getenv("LOCALAPPDATA")` (the one place that *does* read `LOCALAPPDATA` dynamically,
  `AppPaths.getAppDataBase()`, is for locating EliteIntel's *own* AppData folder, not the
  game's — see `INSTALLATION_DISCOVERY_RESEARCH.md` §1 for that distinction). There is no
  filesystem probing, no fallback search across drives, nothing that verifies the guessed path
  actually exists before using it.
- **The only override mechanism is a DB column + manual file picker.** `player.bindings_dir` /
  `player.journal_dir` (via `PlayerDao`) hold a user-entered override. The only writers anywhere
  in the codebase are `BindingsTabPanel.selectBindingsDirectory()` and the equivalent picker in
  `CommonSettingsPanel.java`, both plain `JFileChooser` "Browse…" dialogs. Nothing populates
  these columns automatically.
- **Non-Windows paths are unreliable/dead.** The Linux defaults
  (`~/.var/app/elite.intel.app/ed-bindings`, `~/.var/app/elite.intel.app/ed-journal`) are not
  real Elite Dangerous paths under native, Wine, or Proton — they look like placeholder
  Flatpak-sandbox paths for EliteIntel itself. The `MAC` branches are unreachable: `OsDetector.getOs()`
  (`elite.intel.util.OsDetector`) only ever returns `WINDOWS` or `LINUX` — anything that isn't
  Linux falls through to `WINDOWS`.

This is exhaustively documented already in `INSTALLATION_DISCOVERY_RESEARCH.md` ("Existing
Codebase Findings" §1, §2, §4) — re-verified here, no discrepancy found.

## 3. Classes named for install/path discovery

Searched for naming patterns suggesting this: `install`, `GamePath`, `steamapps`,
`EliteDangerous64`, `registry`, `RegOpenKey`, `PathResolver`, `PathDetector`,
`InstallLocator`/`InstallFinder`.

**Nothing matches.** The two classes whose names sound adjacent are red herrings once read:

- `elite.intel.ai.hands.BindingsLoader` — does **not** locate the bindings folder. It takes
  `PlayerSession.getInstance().getBindingsDir()` as a given and only picks *which `.binds` file
  within that already-known folder* is active, by reading `StartPreset.*.start` and falling back
  to most-recently-modified (`BindingsLoader.java:24-51`).
- `elite.intel.util.AppPaths` — resolves paths for EliteIntel's *own* data (DB, working-copy/
  backup dirs, model dirs), not the game's install or bindings location at all.

No class in the codebase has installation discovery, registry probing, or storefront detection
as its responsibility.

## 4. Distinguishing a real/current install from a stale leftover

**Nothing exists for this, anywhere.** There is no executable-presence check, no version-file
read, no "is this actually a live Elite Dangerous install" validation of any kind. The closest
adjacent things, both of which turn out to be about a different problem:

- `BindingsLoader.getLatestBindsFile()` uses `lastModified()` to pick the newest `.binds` file
  **among multiple preset files inside one already-trusted folder** — this is preset selection,
  not install freshness/validity detection. It has no opinion on whether the folder itself is a
  real, current install vs. a leftover from an old/uninstalled product variant.
- `BindingsWorkingCopyRepository` (`app/src/main/java/elite/intel/ai/hands/BindingsWorkingCopyRepository.java:208-239`)
  compares a SHA-256 baseline hash and `FileTime` between EliteIntel's own working copy of a
  `.binds` file and the live game file, to detect drift and decide whether to refresh the working
  copy from the game file:
  ```java
  FileTime workingModified = Files.getLastModifiedTime(workingCopy);
  FileTime gameModified = Files.getLastModifiedTime(gameFile);
  if (workingModified.compareTo(gameModified) <= 0) {
      copyReplacing(gameFile, workingCopy);
      ...
  }
  ```
  This is content-drift detection between two known files, not install-location validity
  checking — it assumes `getBindingsDir()` already points somewhere real.

There is no equivalent of "does `EliteDangerous64.exe` exist at this path," "does a recognizable
version/manifest file exist," or any recency heuristic applied to the *install folder itself*
(as opposed to files within an already-trusted folder).

## 5. Reacting to a game update changing/removing install files

**Nothing reacts at the install-folder level.** What does exist is narrower and file-level, not
install-level:

- `BindingsMonitor.monitorBindings()` (`app/src/main/java/elite/intel/ai/hands/BindingsMonitor.java:111-166`)
  uses a real `java.nio.file.WatchService` on the **already-resolved** bindings directory,
  watching `ENTRY_MODIFY`/`ENTRY_CREATE` on `*.binds` files to reload bindings and re-run
  conflict/missing-binding checks:
  ```java
  bindingsDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_CREATE);
  ```
  This reacts to **content changes inside a folder it already trusts**, not to the folder
  disappearing, moving, or being replaced by a game update/reinstall. If a game update changed
  *which* folder is correct (e.g. a new product variant under a different path), this watcher
  would have no way to notice — it would just start failing to find files, surfaced only as a
  generic `IOException` → `"Please check the bindings directory. Stopping services."` log/UI
  message (`BindingsMonitor.java:157-159`), not a deliberate update-detection path.
- `AuxiliaryFilesMonitor` similarly watches journal-adjacent auxiliary files
  (`Files.exists(statusPath)` / `Files.exists(filePath)` checks, `app/src/main/java/elite/intel/gameapi/AuxiliaryFilesMonitor.java:161,177`)
  within the already-resolved journal directory — same pattern, same scope limitation.

No startup validation step checks "has the install moved/changed since last run" before these
watchers start; they simply start watching whatever `getBindingsDir()`/`getJournalPath()`
currently resolves to.

## Bottom line

Everything found here matches `INSTALLATION_DISCOVERY_RESEARCH.md`'s existing "Existing
Codebase Findings" section point-for-point, and extends it on the two questions that report
didn't explicitly cover (§4, §5 above) — confirming the same "nothing exists" answer for those
too. There is no registry/VDF/manifest probing, no class responsible for install discovery, no
real/stale-install distinction, and no install-level update reaction anywhere in the codebase.
The only thing that currently plays any role here is the manual `JFileChooser`-backed override
(`player.bindings_dir` / `player.journal_dir`), proven safe and already shipping for the
journal-dir case — exactly the "treat automation as convenience, not guarantee" fallback that
report already recommended as the design's safety net. For BindForge, install discovery is
**net-new work, not an extension of an existing mechanism** — there is nothing in the current
codebase to build on beyond that manual-override pattern itself.
