# Missing Bindings Audit — 2026-06-23

Read-only investigation. No code was written or modified.

## TL;DR

Both methods exist **verbatim today**, in `elite.intel.ai.hands.BindingsMonitor`. This is not a
"build from zero" feature — it's "wire up an existing, already-shipped detection mechanism" for
BindForge onboarding.

- `findMissingGameBindings()` and `checkForMissingBindingsAndPersist()` are real, working code —
  `BindingsMonitor.java:283` and `BindingsMonitor.java:311`.
- "Missing" is **already scoped to EliteIntel's own command-dispatch needs**, not a general
  audit of the full `.binds` action list. The scoping list is `Bindings.GameCommand` — see Q4.
- Triggered automatically: once on every `.binds` file change (background `WatchService` thread)
  and once at app-start (`AppController.startServices()` → `KeyBindCheck.check()`).
- Persisted in a real table — `bindings` (`key_binding TEXT UNIQUE`), defined in
  `00023__schema.sql`, accessed via `KeyBindingDao` / `KeyBindingManager`.
- The "Used / Missing" tables you recalled from prior BindForge audits (`BindForgeTabPanel`) are
  populated directly by these two methods — confirmed, not a guess.

## 1. Do the methods exist?

Yes, both under the exact names given, in `elite.intel.ai.hands.BindingsMonitor`:

```java
// BindingsMonitor.java:283-309
public List<String> checkForMissingBindingsAndPersist() {
    List<String> result = new ArrayList<>();
    Map<String, KeyBindingsParser.KeyBinding> currentBindings = getBindings();
    if (currentBindings == null) {
        log.warn("Bindings not yet loaded, skipping missing binding check");
        return result;
    }

    List<String> oldMissingBindings = keyBindingManager
            .getMissingBindings()
            .stream()
            .map(KeyBinding::getKeyBinding)
            .toList();

    for (String gameBinding : findMissingGameBindings(currentBindings)) {
        String bindingName = humanizeBindingName(gameBinding);
        keyBindingManager.addBinding(bindingName);
        result.add(bindingName);
    }

    for (String gameBinding : findFoundGameBindings(currentBindings)) {
        String bindingName = humanizeBindingName(gameBinding);
        if (oldMissingBindings.contains(bindingName))
            keyBindingManager.removeBinding(bindingName);
    }
    return result;
}

// BindingsMonitor.java:311-317
public List<String> findMissingGameBindings(Map<String, KeyBindingsParser.KeyBinding> currentBindings) {
    if (currentBindings == null)
        return List.of();
    return requiredGameBindings().stream()
            .filter(gameBinding -> currentBindings.get(gameBinding) == null)
            .toList();
}
```

There's also a sibling method, `findFoundGameBindings()` (`BindingsMonitor.java:319-325`), which
is the complement — same scoping list, but filters for bindings that *are* present. It's what
populates the "Used" half of the UI table (see Q2).

No other candidates turned up under similar/renamed names — these are the only hits for
`*MissingBinding*`/`*MissingGameBinding*` anywhere in the source tree (excluding compiled
`.class` files).

## 2. What do they actually do today?

**Scope of "missing":** scoped to EliteIntel's own needs, not a full `.binds` schema audit. See
Q4 for the authoritative source list (`Bindings.GameCommand`) — `findMissingGameBindings()`
filters that list down to entries with no corresponding entry in the currently-parsed bindings
map (`BindingsMonitor.java:314-316`). It does not enumerate every action Frontier's `.binds`
schema supports — only the ~80 actions in `Bindings.GameCommand`.

**Trigger paths (two):**

1. **Automatic, on file change.** `BindingsMonitor.monitorBindings()` runs on a dedicated
   `WatchService` background thread (`BindingsMonitorThread`). On every detected `.binds`
   create/modify event (after a 300ms settle delay and re-parse), it calls both
   `checkForMissingBindingsAndPersist()` and `checkForConflictsAndPersist()`
   (`BindingsMonitor.java:147-148`). It also runs once immediately on monitor startup via
   `parseAndUpdateBindings()` followed by the same checks inside the watch loop.
2. **Automatic, on app start.** `AppController.startServices()` calls
   `KeyBindCheck.getInstance().check()` (`AppController.java:263`), which calls both
   `BindingsMonitor` check methods and publishes voice (`AiVoxResponseEvent`) and log
   (`AppLogEvent`) announcements summarizing newly-found missing bindings and conflicts
   (`KeyBindCheck.java:25-46`).

There is no manual/voice-triggered path found — no `Commands`/`Queries` enum entry invokes
`checkForMissingBindingsAndPersist()` directly. The one voice-facing query,
`AnalyzeMisingKeyBindingQueryCommand` (`check_missing_key_bindings`), only *reads* the already-
persisted list via `KeyBindingManager.getMissingBindings()` — it doesn't trigger a fresh check.

**Persistence — yes, a real table exists today:**

- Table `bindings` (`key_binding TEXT UNIQUE`, `id INTEGER PRIMARY KEY AUTOINCREMENT`), created
  in `app/src/main/resources/db-migration/00023__schema.sql:1-4`.
- Accessed via `KeyBindingDao` (`app/src/main/java/elite/intel/db/dao/KeyBindingDao.java`) and
  wrapped by `KeyBindingManager` (`addBinding`/`removeBinding`/`getMissingBindings`/`clear`).
- Despite the generic table name `bindings`, this table holds **only missing bindings** — rows
  are added when a required binding disappears and removed when it reappears
  (`checkForMissingBindingsAndPersist()`'s diff logic above). It is not a list of all bindings.
- Sibling table `binding_conflicts` (`conflict_key TEXT UNIQUE`, `description TEXT`) exists for
  the analogous conflict-detection feature, created in `00051__schema.sql:30-35`, backed by
  `BindingConflictManager`.

**UI surfacing — confirmed, these methods are exactly what populates the Used/Missing tables.**

`BindForgeTabPanel.initData()` (`app/src/main/java/elite/intel/ui/screen/BindForgeTabPanel.java`)
calls both methods directly, live, against the currently-loaded working-copy bindings (not the
persisted DB rows):

```java
// BindForgeTabPanel.java:261-282
List<String> usedBindings = monitor.findFoundGameBindings(parsedBindings).stream()
        .sorted(String::compareToIgnoreCase)
        .toList();
renderGroupedTables(usedBindingsPanel, groupedBindings(usedBindings, slots), ...);
tabs.setTitleAt(0, getText("bindings.usedBindings", usedBindings.size()));

List<String> missingBindings = monitor.findMissingGameBindings(parsedBindings).stream()
        .sorted(String::compareToIgnoreCase)
        .toList();
renderGroupedTables(missingBindingsPanel, groupedBindings(missingBindings, slots), ...);
tabs.setTitleAt(1, getText("bindings.missingBindings", missingBindings.size()));
UiBus.publish(new BindingsSummaryChangedEvent(missingBindings.size(), usedBindings.size()));
```

This matches (and is independently confirmed by) the data-flow already documented in
`dawntreader-docs/Reports/BINDINGS_UI_ANALYSIS.md:276-291` from a prior BindForge audit — so the
"Used/Missing tables" Krondor referenced are this exact pair of methods, not a separate
not-yet-built feature.

Note the UI path calls these methods **live, on demand**, against a freshly-parsed working copy
— it does not read from the `bindings` DB table. The DB table is a separate, longer-lived record
used only for the cross-session diff/announcement path (`KeyBindCheck` on app start, and the
`check_missing_key_bindings` voice query). Both paths share the same detection logic
(`findMissingGameBindings`/`findFoundGameBindings`) but serve different consumers.

## 3. Closest analog (not applicable — methods exist)

N/A — both methods exist under the exact names given, so there was no need to fall back to an
analog search. For completeness: `BindingsMonitor.checkForConflictsAndPersist()`
(`BindingsMonitor.java:197-261`) is the structurally-parallel sibling feature (same class, same
trigger points, same diff-against-DB pattern, backed by `binding_conflicts` instead of
`bindings`). The missing-bindings feature already follows that exact pattern — it isn't a
candidate to extend, it's the same generation of code, built at the same time, in the same file.

## 4. Does EliteIntel already know its own required bindings (vs. the full game action list)?

Yes — unambiguously. `Bindings.GameCommand`
(`app/src/main/java/elite/intel/ai/hands/Bindings.java:15-159`) is exactly that list: an enum of
~80 entries, each holding the Frontier action-name string EliteIntel depends on for command
dispatch (e.g. `BINDING_GALAXY_MAP("GalaxyMapOpen")`, `BINDING_PRIMARY_FIRE("PrimaryFire")`).
Per its own doc comment in `PACKAGE.md:334-343` and `Bindings.java`'s class doc, this is "the
authoritative registry of Elite Dangerous action names that EliteIntel commands and queries may
invoke" — explicitly the EliteIntel-required subset, not the full `.binds` schema (Frontier's
schema has hundreds of actions; this enum has ~80, several of which intentionally alias the same
binding ID for different logical commands, e.g. `BINDING_FOCUS_STATUS_PANEL` and
`BINDING_FOCUS_INTERNAL_PANEL` both → `"FocusRightPanel"`).

`BindingsMonitor.requiredGameBindings()` (`BindingsMonitor.java:327-333`) is the bridge: it
de-duplicates `Bindings.GameCommand` values into the list both `findMissingGameBindings()` and
`findFoundGameBindings()` filter against. This is the single source of truth the whole
missing/used detection feature is scoped to.

## Bottom line

For BindForge onboarding: this is **wiring**, not **building**. The detection logic, the
required-bindings scope list, the persisted missing-bindings table, the automatic triggers (app
start + live file watch), and the UI tables that surface "Used" vs. "Missing" all exist today
and are exercised in production. Whatever onboarding flow you design can call
`BindingsMonitor.getInstance().findMissingGameBindings(...)` (or read the already-maintained
`bindings` DB table via `KeyBindingManager.getMissingBindings()`) directly rather than
re-deriving "what does EliteIntel need" from scratch. The main open design question is UX, not
detection: today missing bindings only get announced passively (voice/log) or shown in the
existing Bindings tab table — there's no existing "offer to auto-fill" or guided-assignment flow
on top of this data.
