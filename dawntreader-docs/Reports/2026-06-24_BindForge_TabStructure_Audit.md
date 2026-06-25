# BindForge Tab Structure Audit — 2026-06-24

Read-only investigation, no code changes. Goal: determine what's needed to turn the current
single-screen BIND FORGE tab into a sub-tab strip — "Binding Profile" (today's screen, unchanged)
plus a new "Binding Management" sub-tab — without breaking anything that currently addresses
`BindForgeTabPanel` or its contents.

## 1. How the top-level tab strip works today

`elite.intel.ui.screen.AppView` builds it in `buildUi()`:

```java
// AppView.java:93
JTabbedPane tabs = AppTheme.makeMainNavTabs();
...
// AppView.java:114-120
tabs.addTab(getText("tab.ai"), aiIcon, aiTabPanel);
tabs.addTab(getText("tab.commander"), commanderIcon, commanderTabPanel);
tabs.addTab(getText("tab.actions"), actionsIcon, actionsTabPanel);
tabs.addTab(getText("tab.bindForge"), bindForgeIcon, bindForgeTabPanel);
tabs.addTab(getText("tab.settings"), settingsIcon, settingsTabPanel);
tabs.addTab(getText("tab.stats"), statsIcon, usageStatsTabPanel);
tabs.addTab(getText("tab.manual"), manualIcon, userManualPanel);
```

`AppTheme.makeMainNavTabs()` (`AppTheme.java:670`) returns `new HudTabbedPane(HudTabbedPane.Level.MAIN_NAV)`.

`HudTabbedPane` (`app/src/main/java/elite/intel/ui/widget/HudTabbedPane.java:20`) **extends
`JTabbedPane` directly** — it is a plain `JTabbedPane` with a custom `BasicTabbedPaneUI` subclass
(`HudTabbedPaneUi`, line 70) installed for HUD-themed painting (tab box fills, icon tinting,
upper-cased labels, etc.). All standard `JTabbedPane` API — `addTab`, `getComponentAt`,
`setSelectedIndex`, `addChangeListener` — works unmodified; only rendering is overridden. So:
**plain `JTabbedPane` (subclassed for styling only), not some unrelated custom component.**

`Level` (`HudTabbedPane.java:26-35`) is an enum selecting visual density: `MAIN_NAV` (the app-level
strip), `SECTION` (sub-navigation within a screen — see Q2), `COMPACT`, `STANDARD`.

## 2. Does a nested sub-tab pattern already exist?

**Yes — twice**, both inside `ui.screen`, both using the same recipe. Per Open/Closed/DRY, the
new Binding Profile / Binding Management split should reuse this, not invent a new one.

**`ActionsTabPanel`** (`app/src/main/java/elite/intel/ui/screen/ActionsTabPanel.java:23-34`):
```java
JTabbedPane tabs = AppTheme.makeSectionTabs();
tabs.setTabPlacement(JTabbedPane.TOP);
tabs.addTab(getText("actions.tab.commands"), commandCatalogTablePanel);
tabs.addTab(getText("actions.tab.customCommands"), customCommandsTabPanel);
add(tabs, BorderLayout.CENTER);
```

**`SettingsTabPanel`** (`app/src/main/java/elite/intel/ui/screen/SettingsTabPanel.java:44-63`):
same shape — `AppTheme.makeSectionTabs()`, three `addTab(...)` calls for AI Services / Audio /
Push To Talk, plus a `ChangeListener` (line 54) used there for an unsaved-changes guard (not
needed for BindForge, but shows the hook point if ever needed).

`AppTheme.makeSectionTabs()` (`AppTheme.java:675-677`) returns
`new HudTabbedPane(HudTabbedPane.Level.SECTION)` — the `Level.SECTION` Javadoc
(`HudTabbedPane.java:28`) literally says *"Sub-navigation within a screen section: compact,
normal weight, no content border."* This is the purpose-built pattern for exactly this ask.

Both existing call sites are top-level tab panels (added directly to `AppView`'s outer
`JTabbedPane`) that put an inner `AppTheme.makeSectionTabs()` JTabbedPane in their `CENTER`,
exactly the shape BindForge needs: outer tab panel → inner section-tabs → "Binding Profile" /
"Binding Management" as the two inner tabs.

(Note: `BindForgeTabPanel` itself already uses a *third* level — `AppTheme.makeCompactTabs()` /
`Level.COMPACT`, line 171 — for its own Used/Missing Bindings inner tabs. That's a different,
denser style reserved for data-panel tabs per the Javadoc; it's not the right level for the new
outer Binding Profile/Management split — `makeSectionTabs()` is.)

## 3. Is "BINDING PROFILE" part of `BindForgeTabPanel`'s own layout, or injected by a parent?

**Entirely `BindForgeTabPanel`'s own code. `AppView` has zero knowledge of it.**

`AppView` only does `tabs.addTab(getText("tab.bindForge"), bindForgeIcon, bindForgeTabPanel)`
(`AppView.java:117`) — it hands the whole panel instance to the outer `JTabbedPane` as one opaque
tab's content and never looks inside it.

Inside `BindForgeTabPanel.buildUi()` (`BindForgeTabPanel.java:158-179`):
```java
private void buildUi() {
    setLayout(new BorderLayout(2, SCREEN_TOP_GAP));
    setBorder(hudSubtabContentBorder());
    setBackground(HUD_COLOR_ROLE_APPLICATION_BACKGROUND);

    JPanel details = compactProfilePanel();
    add(bindingProfileCard(details), BorderLayout.NORTH);   // <- "BINDING PROFILE" header lives here

    usedBindingsPanel = groupedTablesPanel();
    missingBindingsPanel = groupedTablesPanel();
    ...
    tabs = AppTheme.makeCompactTabs();                       // Used/Missing inner tabs
    tabs.addTab(getText("bindings.usedBindings"), nestedTabContent(usedBindingsScrollPane));
    tabs.addTab(getText("bindings.missingBindings"), nestedTabContent(missingBindingsScrollPane));
    add(tabs, BorderLayout.CENTER);

    add(buildFooter(), BorderLayout.SOUTH);                  // Fix Missing / Revert / Apply
}
```

The header itself comes from `bindingProfileCard()` (`BindForgeTabPanel.java:223-237`):
```java
private JComponent bindingProfileCard(JPanel body) {
    HudSection card = new HudSection(
            getText("bindings.section.profile"),   // "Binding Profile" (gui.properties:107)
            new BorderLayout(),
            HudPanel.Variant.FLAT,
            6);
    ...
}
```
`HudSection`'s constructor (`HudSection.java:79`) renders the title as
`headerLabel = AppTheme.hudSectionLabel(title.toUpperCase())` — that's what paints "BINDING
PROFILE" in the red box from the screenshot.

**What would need to move:** nothing needs to move *out* of the class — the entire current
`buildUi()` body (NORTH profile card + CENTER used/missing tabs + SOUTH footer) is one cohesive
unit that becomes the content of the new inner "Binding Profile" sub-tab as-is. The change is
structural at one level only: introduce an inner `AppTheme.makeSectionTabs()` JTabbedPane (Q2
pattern) with two tabs — "Binding Profile" (wrapping today's unchanged content, most cleanly by
extracting today's `BindForgeTabPanel` body into its own panel class, the same way
`ActionsTabPanel` delegates to `CommandCatalogTablePanel`/`CustomCommandsTabPanel`) and "Binding
Management" (the new backup/restore panel). The `getText("bindings.section.profile")` header
inside that content can stay exactly as it is, or be dropped if the new sub-tab label already
says "Binding Profile" — that's a design call for the implementation step, not something this
audit needs to resolve.

## 4. Does anything reference `BindForgeTabPanel` (or its children) by tree position?

**No. Confirmed by exhaustive grep — safe to nest.**

- `grep -r "getComponentAt|getTabComponentAt|indexOfComponent|indexOfTab(|getSelectedComponent()|\.getComponent(\d"` across all of `app/src` (main + test): **zero matches anywhere in the codebase.** No code walks any `JTabbedPane` by index/position.
- Every reference to `BindForgeTabPanel` outside the class itself is in `AppView.java`, and every one of them is a direct field/method call, never a tree lookup:
  - `private BindForgeTabPanel bindForgeTabPanel;` (`AppView.java:45`)
  - `bindForgeTabPanel = new BindForgeTabPanel();` (`AppView.java:107`)
  - `tabs.addTab(getText("tab.bindForge"), bindForgeIcon, bindForgeTabPanel);` (`AppView.java:117`) — stores the instance as opaque tab content, nothing reads it back out by index
  - `bindForgeTabPanel.promptCloseWithDraft();` (`AppView.java:67`, window-close handler)
  - `bindForgeTabPanel.initData();` (`AppView.java:151`)
  - `bindForgeTabPanel.dispose();` (`AppView.java:177`, language-change rebuild)
- `grep` for `BindForgeTabPanel|bindForgeTabPanel` outside `app/src/main` (e.g. in `app/src/test`): **zero matches.** No test references this class, walks its tree, or asserts on its internal structure.
- The panel's child fields (`bindingsDirField`, `profileField`, `filePathField`, `usedBindingsPanel`, `missingBindingsPanel`, `applyButton`, `revertButton`, `fixAllButton`, etc.) are all `private` to `BindForgeTabPanel` — grep confirms no other class references them by name at all.
- The only way the rest of the app learns anything about BindForge state is via EventBus, not tree inspection: `BindingsSummaryChangedEvent`, `KeymapSyncStateChangedEvent`, `BindingsUpdatedEvent`. The one external consumer, `AiTabPanel` (`AiTabPanel.java:458-478`), reads only the event's own payload (`event.missing()`, `event.inSync()`) — it never reaches into `BindForgeTabPanel`'s component tree.

Nesting today's content one level deeper (inside a new inner `JTabbedPane`) changes nothing about
any of these access paths — `AppView` still holds the same `bindForgeTabPanel` reference and calls
the same public methods; the EventBus publishers/subscribers are untouched; no index-based lookup
exists to break.

## 5. i18n convention for the new sub-tab labels

Yes, an established convention exists, and it's actually two coexisting patterns worth
distinguishing:

- **Top-level tab labels** use flat `tab.<name>` keys: `tab.ai`, `tab.commander`, `tab.actions`,
  `tab.bindForge` = "Bind Forge", `tab.settings`, `tab.stats`, `tab.manual`
  (`gui.properties:7-15`).
- **Existing inner section-tab labels** (the `Level.SECTION` pattern from Q2) use
  `<screen>.tab.<name>`: `actions.tab.commands` = "Built-in Commands",
  `actions.tab.customCommands` = "Custom Commands" (`gui.properties:137-138`);
  `settings.tab.aiServices` = "AI Services", `settings.tab.audio` = "Audio",
  `settings.tab.comms` = "Push To Talk" (`gui.properties:430,431,457`).

This is the convention to follow for the two new BindForge sub-tabs — e.g.
`bindForge.tab.bindingProfile` = "Binding Profile" and `bindForge.tab.bindingManagement` =
"Binding Management", matching the existing `bindForge` camelCase prefix already used for
`tab.bindForge`.

One nuance worth flagging: `BindForgeTabPanel`'s own *existing* inner tabs (Used/Missing
Bindings, `Level.COMPACT`) do **not** follow the `<screen>.tab.<name>` pattern — they use flatter
domain keys, `bindings.usedBindings` / `bindings.missingBindings` (`BindForgeTabPanel.java:172-173`),
because their label text is dynamic (`"Used Bindings (12)"` via a `{0}` parameter, see
`tabs.setTitleAt(0, getText("bindings.usedBindings", usedBindings.size()))`, line 295). That's a
different, pre-existing naming lineage for that one panel and isn't the pattern to copy for the
new outer split — `<screen>.tab.<name>` (matching Actions/Settings) is.

All seven locale files (`gui.properties`, `gui_de`, `gui_es`, `gui_fr`, `gui_pt`, `gui_ru`,
`gui_uk`) carry independent copies of each key, so any new keys need entries in all seven.
Worth noting in passing: `gui_es.properties` is already missing `bindings.section.profile` (only
`tab.bindForge` was found there), so full per-locale parity isn't 100% guaranteed today —
something to check when adding the new keys, not a blocker.

## Bottom line

**No, nesting `BindForgeTabPanel`'s existing content under a new inner sub-tab would not break
anything that currently references it.** Every external reference — `AppView`'s field, its four
method calls, and the EventBus events the rest of the UI listens for — addresses the panel
through its own public methods/fields and event payloads, never through component-tree position
or index. No test, no `getComponentAt`/`indexOfTab`-style code, and no other class anywhere in the
codebase walks or assumes the shape of `BindForgeTabPanel`'s internal tree. The implementation
path is also already paved by an existing, matching pattern (`ActionsTabPanel` /
`SettingsTabPanel` + `AppTheme.makeSectionTabs()`) — reuse it rather than introducing a new nested-
tab mechanism.
