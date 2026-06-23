# Git Sync Report — 2026-06-20

## Starting state

- Branch: `V1.1-KAN-6-push-to-talk-dawntreader`
- Up to date with `origin/V1.1-KAN-6-push-to-talk-dawntreader`
- Working tree: modified `dawntreader-docs/Reports/BindForge_Data_Model.md` and `dawntreader-docs/Reports/BindForge_Punch_List.md` — left untouched throughout this sync

## Remotes

- `origin` → https://github.com/SudoKrondor/EliteIntel.git (DawnTreader fork)
- `upstream` → https://github.com/stone-alex/EliteIntel.git (stone-alex/EliteIntel)

## Fetch results

Both `origin` and `upstream` advanced `V1.1` identically: `bb164036` → `15a189ef` (fork is in sync with upstream, no divergence between the two). `origin` also gained a new branch `V1.1-unit-tests`; `upstream` gained the same branch plus 2 new commits on `upstream/V1.1-KAN-6-push-to-talk-dawntreader` (`0f2e5437..04b54cdc`) that are already present locally via a prior merge.

## Local V1.1 update

Local `V1.1` was 7 commits behind `origin/V1.1` / `upstream/V1.1`, with no local-only commits — a clean fast-forward. Updated via `git fetch origin V1.1:V1.1` (without checking out the branch) to `15a189ef`.

New commits pulled into `V1.1`:
- Fix materials on planet not being saved (`09dff751`)
- Get primary star field by systemAddress (`bb164036`)
- Unit tests for Subscribers, parts 1–3 (`2fb907b0`, `f6f212e2`, `15e0632e`, `55cf83b4`, `15a189ef`) — adds 32 new subscriber test classes under `app/src/test/java/elite/intel/junit/gameapi/journal/subscribers/`, plus small supporting changes to `LocationDao`, `ShipDao`, `Database.java`, `EdsmApiClient`, `SpanshClient`, `BaseEvent`, `LocationDto`, several journal subscribers, and `log4j2.xml`

## Merge into working branch

Merged `V1.1` (`15a189ef`) into `V1.1-KAN-6-push-to-talk-dawntreader` via `git merge V1.1 --no-edit`.

**Result: clean merge, no conflicts** (merge strategy `ort`, merge commit `7504245b`). 44 files changed (3166 insertions, 39 deletions) — entirely new subscriber test files plus the small fixes listed above; nothing touched push-to-talk or BindForge files.

## End state

- Branch `V1.1-KAN-6-push-to-talk-dawntreader` now 6 commits ahead of `origin/V1.1-KAN-6-push-to-talk-dawntreader`, up to date with `V1.1` (`15a189ef`)
- HEAD: `7504245b`
- No `git push` performed — local only
