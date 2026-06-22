# Bindings Backup/Restore Mechanism — Read-Only Audit

## TL;DR

The "ugly" characterization checks out, and there's a concrete git-history incident behind it,
though it's more nuanced than "currently broken." Today's *active* backup path is sound on its
own terms: `BindingsApplyService.apply()` (`app/src/main/java/elite/intel/ai/hands/BindingsApplyService.java:118-131`)
creates a timestamped, collision-safe, **never-overwritten** copy of the live game `.binds` file
in `elite-intel/bindings/backups/` before every apply — but only on apply, and **only if the
user clicks Apply**; there is **no automatic first-run backup** of existing bindings before
BindForge touches anything, which is exactly the gap the BindForge punch list already flags as
a new hard requirement. Backups **never expire and nothing ever cleans the directory** — it
grows by one file per apply, forever. There is **no restore-from-backup UI anywhere** — "Revert"
in `BindForgeTabPanel` discards the in-app draft and reloads from the live game file; it has
nothing to do with the timestamped backups sitting in `elite-intel/bindings/backups/`, which a
user could only restore manually via a file manager today. Git history confirms a real,
already-fixed incident: for nine days (`f90339a4`, 2026-06-08 → `b4225482`, 2026-06-17) the apply
path had **no conflict detection at all** — applying a stale EI draft would silently overwrite
any bindings changes the user made directly in-game in the meantime, with no warning. That
specific bug never reached a tagged release (this whole feature only exists on unreleased `V1.1`
work — no `v-1.0.x` tag contains it), so it's a dev/internal-testing incident, not a shipped
regression — which matches "has caused real problems for some users" if "users" means the
internal testing group, not the public release base. Separately, there's dead, **inconsistent**
backup code still sitting in the working-copy layer (`BindingsWorkingCopyRepository.save()`)
that *would* silently overwrite a single `.bak` sibling on every call — it has zero callers
today, so it can't bite anyone right now, but it's exactly the kind of leftover that makes the
mechanism look "ugly" on inspection even though it's currently inert.

---

## 1. What exists today — triggers, scope, storage, cleanup

Three classes form the mechanism, all under `app/src/main/java/elite/intel/ai/hands/`, plus path
resolution in `elite.intel.util.AppPaths`.

### Storage locations (`AppPaths.java:39-51`)

```java
/** Returns the directory for per-preset working copies, creating it if needed. */
public static Path getBindingsWorkingDir() throws IOException {
    Path dir = getAppDataBase().resolve("elite-intel/bindings");
    Files.createDirectories(dir);
    return dir;
}

/** Returns the directory for timestamped game-file backups, creating it if needed. */
public static Path getBindingsBackupDir() throws IOException {
    Path dir = getAppDataBase().resolve("elite-intel/bindings/backups");
    Files.createDirectories(dir);
    return dir;
}
```

Both live under the app's own data directory (`%LOCALAPPDATA%` on Windows, XDG data home on
Linux/macOS via `getAppDataBase()`, `AppPaths.java:53-67`) — never inside the game's own
bindings folder. **This was not always true** — see Section 2's history.

### What actually triggers a backup today

Exactly one path: the user clicking **Apply** in `BindForgeTabPanel` →
`performApply()` (`app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java:313-336`) →
`BindingsApplyService.apply()` (`BindingsApplyService.java:54-82`):

```java
// BindingsApplyService.java:54-82 (abridged)
public Path apply(String presetFileName, Path gameBindsFile) throws BindingsApplyException {
    Path workingCopy = workingCopyRepo.getWorkingCopyPath(presetFileName);
    if (!Files.exists(workingCopy)) {
        throw new BindingsApplyException("No working copy found for preset: " + presetFileName);
    }
    if (!verifyGameFileDidNotChange(presetFileName, gameBindsFile)) {
        log.info("No bindings draft to apply for '{}'", presetFileName);
        return null;
    }
    ...
    Path backupPath = backupGameFile(gameBindsFile);
    Path result = writeToGameDir(gameBindsFile, content, backupPath);
    ...
}
```

`backupGameFile()` (`BindingsApplyService.java:118-131`) does the actual backup:

```java
private Path backupGameFile(Path gameBindsFile) throws BindingsApplyException {
    if (!Files.exists(gameBindsFile)) {
        log.info("No existing game file to back up at {}", gameBindsFile);
        return null;
    }
    try {
        Path backupDir = backupDirectory != null ? backupDirectory : AppPaths.getBindingsBackupDir();
        Path backupPath = backupService.createBackup(gameBindsFile, backupDir);
        log.info("Backed up game bindings to {}", backupPath);
        return backupPath;
    } catch (IOException e) {
        throw new BindingsApplyException("Could not create backup of game bindings: " + e.getMessage(), e);
    }
}
```

**What gets backed up:** only the single live game `.binds` file for the preset currently being
applied — not `StartPreset.*.start`, not `.buttonMap` files, not any other preset the user might
have. **Nothing today backs up bindings before the user has made any edits at all** — there is
no "on first run, snapshot everything" step anywhere in this code. (`BindForge_Punch_List.md:68-74`
already calls this out as a new, separate, "mandatory first-load backup, non-negotiable"
requirement for the onboarding feature — i.e. the planning doc independently arrived at the same
gap this audit found in the code.)

### How the backup file itself is created (`BindingsBackupService.java:35-61`)

```java
public Path createBackup(Path bindsFile, Path targetDirectory) throws IOException {
    Files.createDirectories(targetDirectory);
    String timestamp = ZonedDateTime.now(clock).format(BACKUP_TIMESTAMP);
    String baseName = bindsFile.getFileName() + "." + timestamp;

    for (int attempt = 0; attempt < 100; attempt++) {
        String suffix = attempt == 0 ? "" : "-" + attempt;
        Path backup = targetDirectory.resolve(baseName + suffix + ".bak");
        if (backup.getFileName().toString().endsWith(".binds")) {
            throw new IOException("Backup filename must not end with .binds: " + backup);
        }
        if (!Files.exists(backup)) {
            return Files.copy(bindsFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
    throw new IOException("Could not create a unique backup filename for " + bindsFile);
}
```

Filenames are `<originalName>.<yyyyMMdd-HHmmss>[-N].bak` — timestamped and collision-checked
(the `-N` suffix loop guards against two backups in the same second). The `.bak` extension is
deliberate, per the class's own doc comment (`BindingsBackupService.java:11-16`): "Backup names
intentionally do not end with `.binds`; otherwise Elite Dangerous and `BindingsLoader` could
treat backups as loadable profiles."

### Cleanup / retention — **none**

Grepped the entire codebase for cleanup/retention/pruning logic touching the backups directory
(`prune`, `retention`, `cleanup`, `MAX_BACKUPS`, and the `bindings/backups` path itself) — zero
matches outside the four files already covered above. **Every successful apply with a real
pending draft adds exactly one more `.bak` file to `elite-intel/bindings/backups/`, forever.**
Nothing ever deletes, rotates, or caps them. For a feature meant to run repeatedly during normal
use (every time a user tweaks a binding and applies it), this directory has no upper bound.

---

## 2. Git history — what's actually gone wrong here

The whole feature is young — first introduced 2026-06-02, currently still unreleased (see
"release status" note at the end of this section) — but it went through two distinct rounds of
hardening, both worth knowing about.

### Round 1 — original design backed up *into the game's own folder*, one backup per keystroke edit

`3b0c32bc` ("Add safe bindings writer backend", 2026-06-02) introduced `BindingsBackupService`
for the first time. Its original `createBackup()` took **no target-directory parameter** — it
always wrote next to the original file:

```java
// BindingsBackupService.java as of 3b0c32bc
public Path createBackup(Path bindsFile) throws IOException {
    Path directory = bindsFile.getParent();   // <-- the game's own bindings folder
    String timestamp = ZonedDateTime.now(clock).format(BACKUP_TIMESTAMP);
    String baseName = bindsFile.getFileName() + ".elite-intel-backup-" + timestamp;
    ...
}
```

And the original `BindingsWriter.assignKeyboardKey()` called this **before every single
keyboard-binding edit**, writing each edit's pre-image straight into the live Elite Dangerous
`ControlSchemes`/bindings folder (`if (!createBackup(edit.file())) { return BindingSaveResult.BACKUP_FAILED; }`,
same commit). In other words: the earliest version of this feature edited the user's real game
file directly, and littered that same real game folder with one `*.elite-intel-backup-*.bak`
file per edit — exactly the kind of mess a non-coder would correctly call "ugly" on sight if they
ever opened that folder.

This was abandoned six days later, not patched: `f90339a4` ("KAN-41: Add safe-write working copy
layer to bindings editor", 2026-06-08) introduced the working-copy concept that exists today
(`BindingsWorkingCopyRepository`) — edits now happen in `elite-intel/bindings/`, never touching
the live game file until an explicit Apply, and the backup-on-every-edit behavior was dropped
from `BindingsWriter` entirely (today's `BindingsWriter.java` contains no backup logic at all —
confirmed by reading the full current file). `BindingsBackupService.createBackup` gained the
`targetDirectory` overload at the same time, redirecting backups out of the game folder and into
`elite-intel/bindings/backups/`. **This earlier, in-game-folder design never shipped in a tagged
release** (see release-status note below) — it was replaced before any `v-1.0.x` tag was cut from
a branch containing it.

### Round 2 — the apply path could silently overwrite in-game changes with a stale draft (real incident, since fixed)

For the nine days between `f90339a4` (2026-06-08) and `b4225482` ("Fix bindings draft apply
overwriting game changes", 2026-06-17), `BindingsApplyService.apply()` had **no check at all**
for whether the live game file had changed since the EI working copy was imported. The diff of
the fix shows exactly what was missing — `apply()` used to go straight from "does a working copy
exist" to "write it to the game directory," with no conflict check in between:

```diff
 if (!Files.exists(workingCopy)) {
     throw new BindingsApplyException("No working copy found for preset: " + presetFileName);
 }
+if (!verifyGameFileDidNotChange(presetFileName, gameBindsFile)) {
+    log.info("No bindings draft to apply for '{}'", presetFileName);
+    return null;
+}
```

Concretely: if a user opened BindForge (importing a working copy), then went back into Elite
Dangerous itself and rebound something there, then returned to BindForge and clicked Apply —
the old code would overwrite the user's just-made in-game change with EI's now-stale draft,
**without any warning**, and without the user necessarily realizing their fresh in-game change
had just been discarded. A backup of the pre-overwrite game file *would* have been created
(the backup-on-apply path already existed), so the data wasn't unrecoverable, but nothing told
the user this had happened or that they should go look for it. The fix added
`verifyGameFileDidNotChange()` (`BindingsApplyService.java:84-96`), which now throws a
user-facing `bindings.apply.conflict` error ("The game bindings file changed after this EI draft
was created. Reload from game or discard the draft before applying.") instead of silently
overwriting. A follow-up commit 49 minutes later (`8f1eb41c`, "Fix bindings apply safety and
i18n choice parsing") turned out, on inspection of the diff, to be a `MessageFormat`
variant-picking fix for localized strings (handling `|` inside `{...}` correctly) rather than a
second file-safety fix — likely needed to get the apply-result message itself displaying
correctly, not a second instance of the overwrite bug.

**Release status of this incident:** neither `f90339a4` nor `b4225482` is an ancestor of any
`v-1.0.x` tag (checked via `git merge-base --is-ancestor` against every `v-1.0.*` tag) — this
entire bindings-editor feature, including both the original "backup into the game folder" design
and the later overwrite bug, has only ever existed on unreleased `V1.1` branch work. So whatever
problems the user is recalling happened during internal/dev use of `V1.1`, not in a release that
reached the broader public user base — worth keeping in mind when deciding how urgently this
needs reworking versus just being aware of it.

---

## 3. Is there any restore-from-backup UI? — **No**

Searched `BindForgeTabPanel.java` (the only bindings-editing tab panel; there is no separate
`BindingsTabPanel` class today — `f90339a4`'s diff shows it as `ui/view/BindingsTabPanel.java`
historically, since renamed/relocated to `ui/screen/BindForgeTabPanel.java`) for any
restore-related action, and grepped the whole codebase for `restore`/`Restore` — no hits
anywhere tied to bindings backups.

The only related button is **Revert** (`BindForgeTabPanel.java:59, 225-227, 338-352`):

```java
// BindForgeTabPanel.java:338-352
private void revertFromGame() {
    if (activePresetFileName == null || gameBindingsFile == null) {
        return;
    }
    int response = JOptionPane.showConfirmDialog(
            this,
            getText("bindings.revert.confirm.text"),
            getText("bindings.revert.confirm.title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (response == JOptionPane.YES_OPTION) {
        workingCopyRepo.delete(activePresetFileName);
        initData();
    }
}
```

This **discards the in-app EI draft** (`workingCopyRepo.delete()`) and re-imports fresh from
whatever the live game file currently contains (`initData()` → `loadOrImportFromGame()`). It has
no connection whatsoever to the timestamped `.bak` files sitting in
`elite-intel/bindings/backups/` — it can't restore one, list them, or even tell the user they
exist. The success dialog after a successful Apply does mention the backup filename
(`bindings.apply.success` interpolates `backupPath.getFileName()`, `BindForgeTabPanel.java:318-321`),
so a technically-inclined user could go find that exact file manually in a file browser — but
there is no in-app way to browse the backups folder, pick an older one, or apply it back. **If a
user needs to recover a specific past backup today, their only option is locating and copying it
by hand outside the app.**

---

## 4. Can the mechanism silently overwrite a backup, or does it only ever add new ones?

**Both behaviors exist in the codebase today, in two different classes, and only one of them is
currently reachable.**

### The active, reachable path — never overwrites

`BindingsBackupService.createBackup()` (`BindingsBackupService.java:44-61`, quoted in full in
Section 1) is timestamped and collision-checked: it tries up to 100 suffix variants
(`-1`, `-2`, ... `-99`) before giving up, and only ever picks a filename that doesn't already
exist (`if (!Files.exists(backup))`). This is the path used by every Apply
(`BindingsApplyService.backupGameFile()`). **This path is correct — it only ever adds new
backups, never replaces an existing one.**

### The dormant, dead-code path — overwrites by design, and has zero callers today

`BindingsWorkingCopyRepository.save(String presetFileName, String xmlContent)`
(`BindingsWorkingCopyRepository.java:88-111`) is a different, separate backup mechanism that
**does** silently overwrite:

```java
public void save(String presetFileName, String xmlContent) throws IOException {
    Path workingCopy = getWorkingCopyPath(presetFileName);
    Files.createDirectories(workingCopy.getParent());

    if (Files.exists(workingCopy)) {
        Path bak = workingCopy.resolveSibling(workingCopy.getFileName() + ".bak");
        try {
            Files.copy(workingCopy, bak, StandardCopyOption.REPLACE_EXISTING);   // <-- overwrite
        } catch (IOException e) {
            log.warn("Could not create .bak for '{}'  proceeding: {}", workingCopy.getFileName(), e.getMessage());
        }
    }
    ...
}
```

This writes a single, fixed-name `<presetFileName>.bak` sibling next to the working copy, and
`StandardCopyOption.REPLACE_EXISTING` means **each call clobbers whatever `.bak` was there from
the previous call** — there's no history, just "the one save before this one." The class's own
doc comment (`BindingsWorkingCopyRepository.java:81-87`) says it's "used for bulk saves (e.g.
reimport on revert)," but that's stale documentation: grepping every call site of
`workingCopyRepo.` in the codebase (main and test source) turns up `getWorkingCopyPath`,
`markApplied`, `hasUnappliedDraft`, `gameFileMatchesBaseline`, `delete`, `loadOrImportFromGame`,
and `exists` — **`save()` itself has zero callers anywhere**, just like `KeyCaptureMapper` in the
separate keyboard-capture investigation. `revertFromGame()` (Section 3) calls `delete()` and
`initData()`, not `save()`. So this overwrite-on-every-call behavior cannot currently bite a real
user — but it's exactly the kind of half-finished, contradictory-with-its-own-docs leftover that
makes the mechanism look "ugly" the moment someone actually reads the code, and it would need to
be either wired up correctly or deleted before building BindForge's onboarding restore feature on
top of this class, so it doesn't surface mid-feature as an inconsistency.

---

## Bottom line

- **What exists today, narrowly:** one backup is created per successful Apply, written to
  `elite-intel/bindings/backups/` with a collision-safe timestamped name that's never reused or
  overwritten. That part is sound in isolation.
- **What's missing relative to what BindForge's onboarding needs:** no automatic backup before
  *any* first touch (only on explicit Apply), no backup of `StartPreset.*.start` or
  `.buttonMap` files (only the single active `.binds` file), no retention/cleanup (grows
  forever), and — the biggest functional gap — **no restore-from-backup UI at all**. "Revert"
  only discards the in-app draft against the live game file; it is not a backup restore.
- **What's actually gone wrong historically:** a real, user-impacting design flaw (writing
  per-edit backups directly into the game's own bindings folder) was used briefly and then
  replaced with the current working-copy approach, and a real, silent-data-loss bug (applying a
  stale draft over fresh in-game changes with zero warning) existed for nine days before being
  fixed with explicit conflict detection. Neither ever reached a tagged release, so the user's
  "has caused real problems for some users" almost certainly refers to internal/dev testing on
  `V1.1`, not the released product.
- **What's currently inert but still messy:** a second, overwrite-by-design backup helper
  (`BindingsWorkingCopyRepository.save()`) sits in the codebase with stale documentation claiming
  it's used for revert, but has no callers at all today.

**Recommendation implied by the above, not a decision:** the single-file, timestamped,
backup-on-apply mechanism is a reasonable foundation to build on rather than something requiring
a full rewrite — but it needs to be *extended* (first-run snapshot of everything, multi-file
coverage, retention policy) and *cleaned up* (resolve or remove the dead `save()` overwrite path)
before BindForge's auto-fill-and-restore onboarding feature can safely sit on top of it, and a
real restore UI has to be built from scratch since none exists today.
