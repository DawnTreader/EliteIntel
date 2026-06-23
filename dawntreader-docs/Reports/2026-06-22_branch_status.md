# Branch Status Report — 2026-06-22

Pure status snapshot. No actions were taken — no pushes, merges, or fetches of new work
(remote refs were fetched read-only to get accurate ahead/behind counts; nothing local changed).

## 1. Current branch and ahead/behind counts

**Current branch:** `V1.1-KAN-6-push-to-talk-dawntreader`
**HEAD:** `dce0e28e` — "docs: BindForge planning/research documentation updates" (committed 2026-06-21 19:05 -07:00)

| Compared to | Ahead | Behind |
|---|---|---|
| `origin/V1.1-KAN-6-push-to-talk-dawntreader` (Krondor's copy) | **7** | **0** |
| `mine/V1.1-KAN-6-push-to-talk-dawntreader` (your fork's copy) | **0** | **0** |
| `origin/V1.1` (Krondor's shared integration branch) | **11** | **26** |

- Your local HEAD and `mine/V1.1-KAN-6-push-to-talk-dawntreader` are **identical** (0 ahead, 0
  behind) — your fork already has everything that's on disk locally.
- Local HEAD is exactly **7 commits ahead** of `origin/V1.1-KAN-6-push-to-talk-dawntreader`, with
  nothing on origin's side that isn't already in your local history. Those 7 commits are:

  ```
  dce0e28e docs: BindForge planning/research documentation updates
  7504245b Merge branch 'V1.1' into V1.1-KAN-6-push-to-talk-dawntreader
  15a189ef Unit tests for Subscribers. part 3
  55cf83b4 Unit tests for Subscribers. part 2
  15e0632e Unit tests for Subscribers. part 1 - test fixes
  f6f212e2 Unit tests for Subscribers. part 1
  2fb907b0 Unit tests for Subscribers. part 1
  ```

- Against `origin/V1.1`, you're 11 ahead / 26 behind — i.e. `origin/V1.1` has picked up 26 commits
  (from other merged work) that this branch doesn't have yet, while this branch has 11 commits
  of its own not yet merged into `origin/V1.1`.

## 2. Default push remote

The current branch's configured upstream (`branch.V1.1-KAN-6-push-to-talk-dawntreader.remote`)
is **`origin`** (`https://github.com/SudoKrondor/EliteIntel.git`), tracking
`origin/V1.1-KAN-6-push-to-talk-dawntreader`. There is no `remote.pushDefault` override set.

**A bare `git push` right now would push to `origin` (Krondor's repo), not `mine` (your fork).**

## 3. Working tree status

```
On branch V1.1-KAN-6-push-to-talk-dawntreader
nothing to commit, working tree clean
```

No staged, unstaged, or untracked changes. Working tree is clean.

## 4. Has origin moved since your last push to `mine`?

Confirmed: **no**. `mine/V1.1-KAN-6-push-to-talk-dawntreader` and local HEAD are byte-for-byte
identical (`dce0e28e`). Comparing `origin/V1.1-KAN-6-push-to-talk-dawntreader` against
`mine/V1.1-KAN-6-push-to-talk-dawntreader` shows **0 commits unique to origin's side** — meaning
origin's copy is a strict subset of what's in `mine`/local. Origin's tip is still
`04b54cdc` ("Merge branch 'V1.1' into V1.1-KAN-6-push-to-talk-dawntreader", 2026-06-20 00:35
-07:00) — unchanged, and Krondor's copy has not received any new commits since your last push to
your fork.

## 5. Other local branches on this machine

| Branch | Last commit | What it looks like (from name/recent history) |
|---|---|---|
| `V1.1` | `15a189ef` "Unit tests for Subscribers. part 3" (2026-06-20) | Krondor's shared integration branch, mirrored locally. No upstream tracking configured on this local copy; 0 ahead / 26 behind `origin/V1.1` — it's stale relative to origin and needs updating if you want to work off latest integration. |
| `master` | `a90433b9` "Merge remote-tracking branch 'origin/master'" (2026-06-08) | Project's root/stable branch. No tracking configured; 7 ahead / 0 behind `mine/master` (your fork's master has 7 fewer commits than local — likely never pushed). |
| `V1.1-KAN-57-elite-intel-devices-dawntreader` | `eb58f02c` "docs: add StarVizion prototype branch report" (2026-06-13) | Finished/parked feature branch (Jira KAN-57, device input work). Identical to `origin/V1.1-KAN-57-elite-intel-devices-dawntreader` (0 ahead/behind that same-named remote branch), and fully merged into `origin/V1.1` already (0 ahead, 191 behind) — this is leftover from completed work, safe to consider for cleanup later. |
| `V1.1-starvizion-prototype-dawntreader` | `34672d1a` "feat(devices): extract elite.intel.devices package for shared SDL3 input" (2026-06-13) | StarVizion prototype branch (device/SDL3 input extraction). Identical to `origin/V1.1-starvizion-prototype-dawntreader` and also fully merged into `origin/V1.1` (0 ahead, 193 behind) — also leftover/parked, same cleanup candidate as above. |

No action was taken on any of this — purely descriptive, per your request.
