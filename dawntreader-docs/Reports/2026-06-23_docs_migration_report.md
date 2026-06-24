# Docs Migration Report — 2026-06-23

Bringing the BindForge planning docs onto `bindforge-gamemode`, which was branched off `V1.1`
and never had them — they only ever existed on `V1.1-KAN-6-push-to-talk-dawntreader` and were
never merged into `V1.1`. Nothing was pushed to `origin` (Krondor's repo) and the old branch was
not touched at any point.

## 1. Confirmed branch and working tree state

```
git branch --show-current   # bindforge-gamemode
git status
```

On `bindforge-gamemode`, up to date with `mine/bindforge-gamemode`. Working tree was clean
except for 6 untracked report files already sitting in `dawntreader-docs/Reports/` that had
never been committed anywhere:

- `dawntreader-docs/Reports/2026-06-22_branch_status.md`
- `dawntreader-docs/Reports/2026-06-22_check_code_integrity.md`
- `dawntreader-docs/Reports/2026-06-22_sync_report.md`
- `dawntreader-docs/Reports/2026-06-23_Missing_Bindings_Audit.md`
- `dawntreader-docs/Reports/2026-06-23_new_branch_report.md`
- `dawntreader-docs/Reports/2026-06-23_sync_report.md`

## 2. Brought `dawntreader-docs/` over from the old branch

Compared the tracked contents of `dawntreader-docs/` on both branches first
(`git ls-tree -r --name-only HEAD -- dawntreader-docs/` vs. the same against
`V1.1-KAN-6-push-to-talk-dawntreader`) to confirm the gap — the old branch had dozens of files
`bindforge-gamemode` was missing entirely: `BindForge_Punch_List.md`, `BindForge_Data_Model.md`,
`BindForge_GameMode_SubGroups.md`, several `BINDFORGE_*`/`*_AUDIT.md` reports, the
`Actual Game Files/` reference `.binds` corpus, `BindScreens/` screenshots, and a few
spreadsheets — none of which overlapped with the 6 untracked files already sitting here.

```
git checkout V1.1-KAN-6-push-to-talk-dawntreader -- dawntreader-docs
```

Result: 122 new/changed paths staged automatically by the checkout (everything that differed
between the two branches' `dawntreader-docs/` trees), with the 6 pre-existing untracked report
files left alone as untracked (the old branch didn't have those paths, so nothing overwrote
them).

## 3. Staged and committed

```
git add dawntreader-docs/
git commit -m "docs: bring BindForge planning docs onto bindforge-gamemode branch ..."
```

Result: commit `d7e8463d`, **128 files changed, 60804 insertions(+), 85 deletions(-)** — the 122
paths copied from the old branch plus the 6 previously-untracked report files, all committed
together since they live in the same folder. (The deletions are line-ending normalization on
`SYNC_REPORT.md`, which existed on both branches with different content.)

## 4. Confirmed the two named docs exist and are readable

```
dawntreader-docs/Reports/BindForge_Data_Model.md   (10,266 bytes)
dawntreader-docs/Reports/BindForge_Punch_List.md   (26,329 bytes)
```

Both opened cleanly. First lines:

> `BindForge_Punch_List.md`: "# BindForge — Running Punch List ... Status: Planning consolidation."
>
> `BindForge_Data_Model.md`: "# BindForge — Data Model Specification ... Status: In progress."

## 5. Pushed to `mine` only

Confirmed the default push target before pushing:

```
git rev-parse --abbrev-ref --symbolic-full-name @{push}
# -> mine/bindforge-gamemode
```

Then a plain `git push`:

```
bccac490..d7e8463d  bindforge-gamemode -> bindforge-gamemode
```

Pushed to `https://github.com/DawnTreader/EliteIntel.git` only.

## 6. Confirmed `origin` untouched and old branch untouched

- `git ls-remote origin bindforge-gamemode` returned nothing both before and after the push —
  Krondor's repo has no knowledge of this branch at all.
- `V1.1-KAN-6-push-to-talk-dawntreader` was never checked out or modified during this task.
  Verified unchanged across all three refs after the push:

| Ref | Commit |
|---|---|
| `V1.1-KAN-6-push-to-talk-dawntreader` (local) | `a429aa54` |
| `mine/V1.1-KAN-6-push-to-talk-dawntreader` | `a429aa54` |
| `origin/V1.1-KAN-6-push-to-talk-dawntreader` | `04b54cdc` |

All three identical to their state before this task — nothing moved.

*(Side note: `git fetch origin` during this verification incidentally pulled a new commit on
`origin/V1.1` — `bccac490..c5651efb` — unrelated to this task. No action was taken on it; noting
it here for visibility only.)*

## End state

- Current branch: `bindforge-gamemode` @ `d7e8463d`
- `dawntreader-docs/` on this branch now matches the old branch's content, plus the 6 reports
  that were only ever local/untracked
- Pushed to `mine` only; `origin` has zero knowledge of `bindforge-gamemode`
- `V1.1-KAN-6-push-to-talk-dawntreader`: untouched, still at `a429aa54` everywhere
