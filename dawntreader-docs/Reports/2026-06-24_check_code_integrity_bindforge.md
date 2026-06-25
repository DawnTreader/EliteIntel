# Code Integrity Review — BindForge Tab Split + Backup Creation — 2026-06-24

Ran `/check-code-integrity` against `826e79db..ef12e9bb` (the tab-split commit `57dcab6a` and
the backup creation/listing commit `ef12e9bb`, both authored by Alan Tonn) before starting the
restore feature on top of `PlayerBackupService`, per Krondor's recommended pre-commit workflow.

## What the review found

15 files in scope (`BindingsLoader.java`, `PlayerBackupService.java` (new),
`BindForgeTabPanel.java`, `BindingManagementPanel.java` (new), `BindingProfilePanel.java` (new,
extracted), `AppPaths.java`, 7 locale properties files, `PlayerBackupServiceTest.java` (new), plus
the prior implementation report). No new dependencies were added (`git diff --stat` on
`*.gradle`/`*.gradle.kts` was empty). Verified the `BindingProfilePanel` extraction is genuinely
byte-for-byte unchanged from the old `BindForgeTabPanel` body (diffed the two ignoring
package/class-name substitutions: the only delta is one added Javadoc block).

### Fixed (low-risk, applied directly)

1. **`BindingManagementPanel.java:91` (now ~93)** — `refreshBackups()` caught `IOException` and
   silently dropped it with only a comment, no log call. CODING_STANDARD is explicit: "Never
   silently skip or ignore errors... Handle errors explicitly and log them with enough context
   (log4j2 is already used project-wide)." Added a `Logger` field and a `log.warn(...)` call.
   Tag: `error-handling`.
2. **`BindingsLoader.java:29`** — the preset-prefix-matched branch of `getLatestBindsFile()`
   called `Files.list(bindingsDir)` without closing the stream (a `DirectoryStream`-backed
   resource leak). This was the third occurrence of the same pattern in this file; the other two
   (`listAllBindsFiles`, `findStartPresetFile`) were already correctly wrapped in
   try-with-resources earlier in the same diff (`ef12e9bb`), making this one's inconsistency
   obvious by contrast. Wrapped it in `try (var stream = Files.list(bindingsDir)) { ... }` to
   match its siblings. No behavior change, same `Optional<Path>` result. Tag: `robustness`,
   `consistency`.

Both fixes were mechanical, behavior-preserving, and directly in files already touched by this
work, so I applied them rather than just flagging them.

### Flagged for review, then resolved per your decisions

3. **`BindingManagementPanel.java` (`performBackup()`)** — `backupService.createBackup()` ran
   synchronously on the EDT when the user clicks "Backup Now." Its sibling panel,
   `BindingProfilePanel.applyPlanInBackground()`, explicitly moves binding-file I/O to a
   background thread because "a large batch would freeze the UI if run on the dispatch thread."
   A backup sweep is normally a handful of small `.binds` files plus one `StartPreset` file, so
   the EDT-blocking window was likely a few milliseconds, but it was inconsistent with the
   established convention in this exact panel family. Tag: `performance`, `convention`.
   **Decision: fix now.** Moved to a background thread, mirroring
   `applyPlanInBackground()`/`setAutoFixBusy()` exactly: "Backup Now" disables itself and shows a
   wait cursor while running, then marshals the result back via `SwingUtilities.invokeLater`.
   Committed separately as `e21b64af`.
4. **`BindingManagementPanel.java` (`performBackup()`)** — the error dialog interpolates the raw
   `IOException.getMessage()` directly into a localized template
   (`bindForge.bindingManagement.backup.error={0}`). That message text is always in English
   regardless of the active UI language. Its sibling, `BindingProfilePanel.performApply()`,
   instead uses `BindingsApplyException.localizationKey()` so the failure reason itself is
   translated. Introducing an equivalent typed/localized exception for `PlayerBackupService`
   would fix this properly but is a real design decision (a new exception type, not a one-line
   change), and the only failure case that currently exists (an empty bindings directory) is rare.
   Tag: `design`, `consistency`.
   **Decision: defer.** Revisit when restore adds more failure modes worth wrapping in one
   localized exception type. Not changed.

### Pre-existing, unrelated to this change set (noted, not touched)

5. **`BindingsLoader.getLatestBindsFile()`** declares `throws Exception` (the broadest possible
   checked exception) rather than something specific. Pre-existing on a widely-called method;
   narrowing it would ripple into every caller's signature, so out of scope for this review.
   Tag: `api`, `design` (pre-existing).
6. **`BindingProfilePanel.initData()`**'s `catch (Exception e)` block (moved unchanged from the
   old `BindForgeTabPanel`) also swallows without logging, the same pattern fixed in finding 1
   above. It predates this change set (verified via the byte-for-byte diff check), so I left it
   alone rather than doing an unrequested refactor of an unrelated method. Worth a follow-up pass
   if you want full consistency. Tag: `error-handling` (pre-existing).

## Test coverage review

`PlayerBackupServiceTest` had 3 tests before this review (sweep + exclude `DeviceMappings.xml`,
same-second collision suffix, newest-first listing). Two real gaps in new-logic coverage:

- The `if (filesToBackup.isEmpty()) throw new IOException(...)` guard clause had zero coverage.
- `listBackups()`'s behavior with no backups created yet had zero coverage.

Added both:
- `createBackupThrowsWhenBindingsDirHasNothingToBackUp`
- `listBackupsReturnsEmptyListWhenNoBackupsExistYet`

`PlayerBackupServiceTest` now has **5 tests, all green**.

## Build/test results (after all fixes)

Ran twice: once after the two low-risk fixes (findings 1-2) plus the new tests, and again after
the EDT-blocking fix (finding 3).

```
./gradlew compileJava compileTestJava
```
**BUILD SUCCESSFUL** (both runs)

```
./gradlew test
```
**BUILD SUCCESSFUL** (both runs) — full default suite passed.
`TEST-elite.intel.ai.hands.PlayerBackupServiceTest.xml`: `tests="5" skipped="0" failures="0"
errors="0"`.

## Commits / push

Three commits, each kept separate from the original feature commits (`57dcab6a`, `ef12e9bb`) and
from each other, per your instruction to commit changes as their own change:

```
cfe224ad fix(bindforge): log swallowed errors and close leaked file streams found by code-integrity review
 3 files changed, 35 insertions(+), 7 deletions(-)

e21b64af fix(bindforge): run Backup Now off the EDT
 1 file changed, 41 insertions(+), 10 deletions(-)
```

Confirmed push target first (same target both times):
```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Pushed:
```
78175e15..cfe224ad  bindforge-gamemode -> bindforge-gamemode
cfe224ad..e21b64af  bindforge-gamemode -> bindforge-gamemode
```
to `https://github.com/DawnTreader/EliteIntel.git` only — `git push origin ...` was never run.

`origin` checked before and after each push via `git ls-remote origin bindforge-gamemode`:
**returned nothing every time** — no knowledge of `bindforge-gamemode` at all, throughout.
`mine/bindforge-gamemode` matches local HEAD exactly (`e21b64af`) after the final push.

## End state

- Current branch: `bindforge-gamemode` @ `e21b64af`
- 2 low-risk findings fixed directly (swallowed-error logging, a leaked file stream)
- 2 findings flagged for your decision before acting: EDT-blocking backup I/O (fixed, per your
  call) and non-localized error message (deferred, per your call)
- 2 pre-existing, unrelated findings noted for awareness only, not touched
- Test coverage gap closed: 2 new tests for previously-uncovered logic branches
- Build and full test suite: green after every change
- `origin`: completely unchanged throughout
- Ready to build restore on top of `PlayerBackupService`
