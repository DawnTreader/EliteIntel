# Bindings Directory Path-Hardcoding Audit

**Date:** 2026-06-24
**Branch:** bindforge-gamemode
**Scope:** read-only investigation, no code changes

## Q1: where the bindings directory comes from

`PlayerSession.getBindingsDir()` (`app/src/main/java/elite/intel/session/PlayerSession.java:705-716`):

```java
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

It's neither purely (a) nor purely (c), it's **(b) with a (c)-shaped fallback**: an OS-specific
hardcoded default is used only when the user hasn't set anything; once the user picks a directory
via `BindingProfilePanel`'s "..." button (`BindingProfilePanel.java:532-543`,
`playerSession.setBindingsDir(path)`), that value is persisted to the `player.bindings_dir` column
(`PlayerDao.java:33,48`) and takes precedence forever after, regardless of OS. `BindingsLoader`
and `BindingsMonitor` both consume this same method
(`BindingsLoader.java:25`, `BindingsMonitor.java:98`), there's no second, independent resolution
path for bindings.

The sibling method `getJournalPath()` (`PlayerSession.java:683-694`) follows the identical
shape for the journal directory, mentioned here only because it shares the bug described in Q3/Q4
below, it is not otherwise in scope.

## Q2: hardcoded literal path strings

Grepped all of `app/src/main` for `Frontier Developments`, `LOCALAPPDATA`, `AppData\Local`,
`C:\Users`, `/home/`, `XDG_DATA_HOME`, `XDG_CONFIG_HOME` (case-insensitive). Every hit:

| File:line | What it is |
|---|---|
| `session/PlayerSession.java:709` | Windows bindings default: `Paths.get(user.home, "AppData", "Local", "Frontier Developments", "Elite Dangerous", "Options", "Bindings")` |
| `session/PlayerSession.java:711` | Linux bindings default: `Paths.get(user.home, ".var", "app", "elite.intel.app", "ed-bindings")` |
| `session/PlayerSession.java:713` | macOS bindings default: `Paths.get(user.home, "Library", "Application Support", "Frontier Developments", "Elite Dangerous", "Options", "Bindings")` |
| `session/PlayerSession.java:687` | Windows journal default (same pattern, journal not bindings) |
| `session/PlayerSession.java:689` | Linux journal default: `.var/app/elite.intel.app/ed-journal` |
| `session/PlayerSession.java:691` | macOS journal default |
| `util/AppPaths.java:77-86` | `getAppDataBase()`: reads `XDG_DATA_HOME` / `LOCALAPPDATA` env vars, falls back to `user.home/.local/share` on Linux/Mac. No literal Frontier/bindings paths here, this is the app's own data dir, already audited and confirmed clean. |
| `util/AppPaths.java:122-138` | `getSecretKeyFile()`: same env-var-driven pattern as `getAppDataBase()`, unrelated to bindings. |
| `ai/hands/BindingsLoader.java:19` | A Javadoc sentence mentioning "Frontier Developments" in prose, not a path literal. |

No raw backslash-joined strings, no drive-letter literals (`C:\`), no literal `/home/` path were
found anywhere. Every path is built with `Paths.get(...)`/`Path.of(...)` from discrete segments
plus `System.getProperty("user.home")` or `System.getenv(...)`, which is the OS-agnostic, correct
way to do this in Java, segments are joined with whatever separator the platform actually uses.

One thing worth flagging even though it isn't "hardcoded to the wrong OS": the Linux bindings/journal
defaults (`.var/app/elite.intel.app/ed-bindings`, `.var/app/elite.intel.app/ed-journal`) don't look
like real Elite Dangerous install locations under Linux. `.var/app/<flatpak-id>` is the Flatpak
per-app data convention, and `elite.intel.app` reads like this app's own identifier, not Frontier's.
This isn't a Windows-style assumption, but it does mean the Linux "auto-detected" default is
unlikely to ever point at a real game install; in practice a Linux user has to override it via the
UI picker regardless. Flagging for awareness, not something this audit was asked to fix.

## Q3: any Windows-only assumption without an equivalent branch?

On paper, no, every one of these methods has three branches (Windows / Linux / else-as-Mac) and
all of them go through `Paths.get(...)`, which is OS-agnostic by construction.

**In practice, yes**, because of a bug in the OS-detection helper they all depend on.
`OsDetector.getOs()` (`util/OsDetector.java:7-9`):

```java
public static OS getOs() {
    return OS.LINUX.getOs().equals(os) ? OS.LINUX : OS.WINDOWS;
}
```

This can only ever return `LINUX` or `WINDOWS`. It never checks for `"mac"`, the `OS.MAC` enum
value exists (`OsDetector.java:12`) but `getOs()` has no code path that returns it. On a real Mac,
`os.name` is something like `"Mac OS X"`, which doesn't equal `"linux"`, so the ternary's false
branch fires and the method incorrectly returns `OS.WINDOWS`.

The consequence for bindings resolution specifically: `PlayerSession.getBindingsDir()`'s `else`
branch (line 713, the one written to hold the macOS default) is **dead code**. A real Mac running
this app today would take the `WINDOWS` branch at line 708-709 instead, defaulting to a
`AppData\Local\Frontier Developments\...` path that doesn't exist on macOS. The user would have to
notice this is wrong and override it via the directory picker, the auto-detection is silently
wrong for them rather than absent.

The same `OsDetector` bug also affects `AppPaths.getAppDataBase()` (`util/AppPaths.java:76-89`),
which checks `OsDetector.getOs() == LINUX || OsDetector.getOs() == MAC` for the
XDG/`.local/share` branch, since `getOs()` can never return `MAC`, a real Mac falls through to the
`WINDOWS` branch there too, which requires `LOCALAPPDATA` and throws
`IllegalStateException("LOCALAPPDATA not set")` instead of silently picking a wrong path. So on
macOS today: bindings/journal directory resolution would silently default to the wrong (Windows)
path, while the app's own data directory resolution (DB, working copies, backups, player backups)
would throw at startup. This is a pre-existing bug unrelated to the BindForge restore work, not
something introduced by it, surfacing it here because it's exactly what Q3 asked about.

Linux detection itself is correct: a real Linux `os.name` does lowercase to `"linux"`, so the
`"linux".equals(os)` check succeeds and `OS.LINUX` is returned correctly. The bug is specific to
macOS never being detected.

## Q4: Proton/Wine on Linux

Nothing in the codebase is aware of this scenario. Grepped `app/src/main` for `proton`, `wine`,
`compatdata`, `steamapps` (case-insensitive); the only hits are the Elite Dangerous in-game
commodity literally named `"Wine"` in two SQL seed migrations
(`db-migration/00009__commodities.sql:234`, `db-migration/01003__schema.sql`), unrelated to the
Wine compatibility layer.

There is no Proton-prefix detection, no Steam `compatdata` path construction, nothing that walks a
Wine prefix's fake Windows filesystem to find the real `.binds` location. Today, a Linux user
running Elite Dangerous under Proton/Wine gets the same generic Linux default described in Q2
(which doesn't point at a real game install anyway) and is expected to manually browse to the
correct path inside their Proton prefix (typically something like
`~/.steam/steam/steamapps/compatdata/<appid>/pfx/drive_c/users/steamuser/AppData/Local/Frontier
Developments/...`) via the existing directory-picker override, exactly the same manual-override
mechanism a misconfigured native install would use. This is consistent with the architecture
elsewhere (the directory is always overridable, never required to be auto-detected correctly),
it's just that there's no special-case help for the Proton case specifically.

## Bottom line

Bindings-directory resolution is **free of literal hardcoded path strings** and uses Java's own
`Path`/`Paths` APIs plus environment-variable/`user.home` lookups throughout, exactly like the
already-audited `AppPaths.getAppDataBase()`. There is no Windows-only code path that's missing a
Linux/Mac equivalent in the source as written.

It is **not fully OS-correct in practice**, though, because of a real, pre-existing bug in
`OsDetector.getOs()` (`util/OsDetector.java:7-9`) that can never return `OS.MAC`. That makes the
macOS branches in `PlayerSession.getBindingsDir()`/`getJournalPath()` (and the macOS branch of
`AppPaths.getAppDataBase()`) unreachable dead code: a real Mac silently takes the Windows path
instead. Linux detection and the Linux branches are correct, the bug is macOS-specific.

Proton/Wine on Linux is **entirely unhandled**, by design rather than oversight, the directory is
always user-overridable and the app makes no attempt to peer inside a Wine prefix. A Linux/Proton
user must manually point the bindings-directory field at the right path themselves, same as any
other non-default install location.
