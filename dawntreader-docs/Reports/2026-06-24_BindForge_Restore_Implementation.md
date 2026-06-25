# BindForge Restore Implementation

**Date:** 2026-06-24
**Branch:** bindforge-gamemode
**Scope:** Restore on top of `PlayerBackupService` (BindForge_Punch_List.md Section G)

## A note on process

The task instructions asked for Step 1's findings to be reported back **before** building, so the
user could sanity-check the foundation. That pause did not happen in real time, building started
right after the investigation. Surfacing this directly: Step 1 was done first and nothing in it
turned out to invalidate the approach, but the explicit "report before proceeding" checkpoint was
skipped in practice. Step 1's findings are reported here, immediately followed by what got built
on top of them, so the assumptions can still be checked against the result.

## Step 1: what was found

**How "draft differs from live" is represented today.** `BindingProfilePanel` has a
`syncStatusBadge` driven by `hasUnappliedChanges()` / `updateSyncStatus()`, which in turn defer to
`BindingsWorkingCopyRepository.hasUnappliedDraft(presetFileName, gameBindsFile)` (SHA-256
baseline-hash comparison against the game file). `BindingProfilePanel` also subscribes to
`BindingsUpdatedEvent` (a no-payload signal on `UiBus`) and calls `initData()` reactively whenever
it fires. This is the mechanism restore relies on: publish `BindingsUpdatedEvent` after writing a
new working copy, and the Binding Profile tab picks it up with zero new coupling.

**`BindingsWorkingCopyRepository.save()`.** Still accurate: zero production callers before this
work. Its Javadoc ("used for bulk saves, e.g. reimport on revert") describes exactly the restore
use case. This confirmed it as the right hook rather than building a parallel write path.

**`BindingsApplyService.apply()`.** Signature is
`public Path apply(String presetFileName, Path gameBindsFile) throws BindingsApplyException`. It
validates the working copy XML, backs up the current game file, atomically writes the working
copy to the game directory, and updates the baseline hash. Returns the backup `Path`, or `null` if
the game file did not exist yet. `restoreToLive()` calls this exact method with the exact
parameters `BindingProfilePanel.performApply()` already uses, so a restored draft goes through
the identical conflict-check and pre-write backup as any other Apply.

**A gap worth naming.** The Punch List's literal phrasing ("loading the selected backup's files
into the working copy") implies every file in a backup folder. In practice a backup folder can
hold several `.binds` files (one per preset) plus `StartPreset.*.start`, but only `.binds` files
have a working-copy/draft concept. `StartPreset.*.start` has no working-copy abstraction, so
restoring it would mean writing directly to the live bindings directory, the "separate, less-safe
direct write path" the task explicitly ruled out. **`StartPreset.*.start` restore is out of scope
for this slice.** Restore acts only on the `.binds` file matching whichever preset
`BindingsMonitor.resolveActiveBindsFile()` currently resolves to active. If the selected backup
has no file for that preset, both restore methods throw `IOException` with a clear message rather
than silently restoring nothing or guessing a different preset.

## Step 2: what got built

**`PlayerBackupService`** (`app/src/main/java/elite/intel/ai/hands/PlayerBackupService.java`)
- `restoreToWorkingCopy(Path backupFolder, String presetFileName)`: reads the backup's file for
  that preset, writes it via `BindingsWorkingCopyRepository.save()`, publishes
  `BindingsUpdatedEvent`.
- `restoreToLive(Path backupFolder, String presetFileName, Path gameBindsFile)`: same first step,
  then `BindingsApplyService.apply(presetFileName, gameBindsFile)`.
- Constructors extended to thread in `BindingsWorkingCopyRepository` and `BindingsApplyService`
  (5-param test-seam constructor; the production no-arg constructor wires real instances of
  both).

**`BindingsMonitor`** (`ai/hands/BindingsMonitor.java`): added
`public File resolveActiveBindsFile() throws Exception`, the same "current monitored file, or
fall back to `BindingsLoader.getLatestBindsFile()`" logic `BindingProfilePanel` already had
inline. `BindingProfilePanel.resolveGameBindsFile()` now delegates to it (behavior-preserving);
restore uses the same shared method so both panels resolve "the active preset" identically.

**`BindingManagementPanel`** (`ui/screen/bindforge/BindingManagementPanel.java`): the backup table
is now single-selection, with two new footer buttons, "Restore to Editing Slot" (subtle) and
"Restore to Live" (primary), enabled only when a row is selected and no operation is in progress.
Both:
1. Resolve the active preset/game file (synchronously, mirroring `BindingProfilePanel`'s existing
   convention) so the confirmation dialog can name the specific file being restored.
2. Show a YES/NO confirmation describing what will be replaced.
3. Run the actual restore on a background thread (`PlayerBackupRestore-Thread`), with the button
   row disabled and a wait cursor, matching the off-EDT pattern already established for
   "Backup Now" and `BindingProfilePanel`'s auto-fix.
4. Marshal the result back via `SwingUtilities.invokeLater`.

"Restore to Editing Slot" shows an error dialog only on failure; on success there is no dialog at
all, the Binding Profile tab already reflects the new draft reactively via `BindingsUpdatedEvent`.
"Restore to Live" shows the same success/error feedback as a normal Apply.

**Shared apply-result presenter.** A code-integrity finding flagged that `BindingManagementPanel`
was duplicating `BindingProfilePanel.performApply()`'s success/error dialog logic almost verbatim
(same message keys, same TTS reminder, same `localizationKey()` handling). Per your direction,
this was extracted into `BindingApplyResultPresenter`
(`ui/support/BindingApplyResultPresenter.java`, alongside the existing
`BindingSaveResultPresenter`), and both `BindingProfilePanel.performApply()` and
`BindingManagementPanel`'s restore-to-live now delegate to it instead of duplicating the dialog
code.

**i18n.** Added `bindForge.bindingManagement.button.restoreToEditingSlot/restoreToLive`,
`...restore.confirm.title`, `...restore.editingSlot.confirm.text`, `...restore.live.confirm.text`,
`...restore.error.dialogTitle`, `...restore.error` to all 7 locale files
(`gui.properties`, `_de`, `_es`, `_fr`, `_pt`, `_ru`, `_uk`). Restore-to-live success/error reuses
the existing `bindings.apply.*` keys via the shared presenter, no new keys needed there.

**Tests.** `PlayerBackupServiceTest` gained 3 tests: `restoreToWorkingCopy` loading a backup as
the new draft, `restoreToWorkingCopy` throwing when the backup has no file for the requested
preset, and `restoreToLive` round-tripping through the real `BindingsApplyService.apply()` (a live
file that has diverged from the backup gets overwritten, and the apply pipeline's own pre-write
backup captures the pre-restore live content). 8/8 tests pass (5 pre-existing + 3 new).

No UI-level tests were added for `BindingManagementPanel`'s button wiring; no panel in this
codebase has Swing-level unit tests, only the underlying services do, this slice follows that
existing convention.

## Step 3: verification

- `./gradlew compileJava compileTestJava` -> BUILD SUCCESSFUL.
- `./gradlew test` (full suite, not just the one test class) -> BUILD SUCCESSFUL.

## Code-integrity review

Ran `/check-code-integrity` against the uncommitted restore work (scoped as the delta since the
last review checkpoint at `e21b64af`, matching "review local work" mode for a feature branch).

No low-risk auto-fixes were needed this time. Two genuine design judgment calls were raised and
resolved with you directly:

1. **Duplication** (`design`): `BindingManagementPanel`'s apply success/error dialogs duplicated
   `BindingProfilePanel.performApply()`'s logic. **You chose to extract a shared presenter now.**
   Done, see `BindingApplyResultPresenter` above.
2. **EDT-adjacent resolution step** (`performance`/`convention`): both restore actions resolve the
   active bindings file synchronously on the EDT (a quick directory listing in the cold-start
   fallback case) so the confirmation dialog can name the preset. **You chose to leave this
   as-is**, since it mirrors `BindingProfilePanel`'s own existing, accepted convention, and the
   actual heavy file-copy/apply work is correctly off the EDT.

One minor gap noted, not fixed: there's no test asserting that `restoreToWorkingCopy()` publishes
`BindingsUpdatedEvent`. This codebase has no existing precedent for asserting on `UiBus`
publications in unit tests, building one for a single one-line side effect (verified correct by
inspection: it only fires after a successful write) was judged disproportionate to the value.

## Commits

- `11086754` feat(bindforge): add restore-to-editing-slot and restore-to-live for player backups
- `4654359f` docs: add dangling check-code-integrity report for BindForge tab-split and backup
  work (leftover from the previous session's review task, never committed)

Pushed to `mine/bindforge-gamemode`. Confirmed `origin` has no knowledge of this branch both
before and after the push (`git ls-remote origin bindforge-gamemode` returned empty both times).
