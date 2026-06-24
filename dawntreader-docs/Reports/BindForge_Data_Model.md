# BindForge — Data Model Specification

**Status:** In progress — being designed section by section.
**Source of truth for:** Java class shapes, field names, types, and rationale for BindForge's in-memory object model.
**Related documents:** `BindForge_Punch_List.md`, `AUDIT_v2_FULL.md`, `BindForge_GameMode_SubGroups.md`

---

## Design Principles

- Three separate in-memory collections (`.binds` elements, `Devices`, `ButtonLabels`) composed inside one loaded `BindingProfile` container. Plain Java object composition — NOT database tables. No binding content is persisted to SQLite.
- Each collection stays close to its own source file's shape for clean round-tripping on save.
- The model exposes cross-referencing lookups (e.g. "resolve the friendly label for this binding's device+key") rather than flattening everything into one merged structure.
- Type safety enforced via inheritance (AXIS/BUTTON/STANDALONE as separate subclasses) rather than a flat class with nullable fields — prevents type-inappropriate properties from being assigned at compile time.
- `<KeyboardLayout>` is a first-class field, not an edge case — must be read, preserved, and written back on every save to support Totem's layout-aware scan code resolution fix.

---

## BindingProfile (top-level container)

Represents one complete loaded state across all four source file types: `StartPreset.4.start`, the active `.binds` file, `DeviceMappings.xml`, and all `.buttonMap` files.

```
BindingProfile
├── generalBindFile: String    // from StartPreset.4.start — the .binds filename assigned to General bind group (e.g. "DualVirpilDawnTreader.4.2")
├── shipBindFile: String       // from StartPreset.4.start — the .binds filename assigned to Ship bind group
├── srvBindFile: String        // from StartPreset.4.start — the .binds filename assigned to SRV bind group
├── onFootBindFile: String     // from StartPreset.4.start — the .binds filename assigned to On Foot bind group
├── keyboardLayout: String     // from <KeyboardLayout> in the .binds file — first-class, must be preserved on save
├── bindings: List<BindingElement>                        // all elements parsed from the .binds file
├── devices: List<DeviceEntry>                            // from DeviceMappings.xml
└── buttonLabels: Map<String, Map<String, String>>        // outer key: device friendly name; inner key: base Frontier name (e.g. "Joy_1"); value: raw label text
```

**Notes:**
- All four `*BindFile` fields are independent — in normal use they all point at the same `.binds` filename, but the split-preset edge case (each group pointing at a different file) is supported without any special logic, since each field is just a string written back to its corresponding line in `StartPreset.4.start`.
- `presetName` is NOT a separate field — the preset name is already implied by the `*BindFile` fields.
- On save: `generalBindFile`/`shipBindFile`/`srvBindFile`/`onFootBindFile` write back to `StartPreset.4.start`; `keyboardLayout` and `bindings` write back to the `.binds` file; `devices` writes back to `DeviceMappings.xml` (fanned out to all discovered installs); `buttonLabels` writes back to the appropriate `.buttonMap` files (fanned out to all discovered installs).

---

## BindingSlot hierarchy

Represents one physical input assignment (one `<Primary>`, `<Secondary>`, or `<Binding>` node).
Shared base type because `Modifier` applies identically to all three node kinds (Punch List A) —
only `Hold` is BUTTON-only.

```
BindingSlot (abstract)
├── device: String
├── key: String
└── modifiers: List<BindingModifier>     // existing BindingModifier(device, key) record, reused as-is

ButtonSlot extends BindingSlot
└── hold: boolean                        // capture-method artifact, never present on AXIS

AxisSlot extends BindingSlot
    // (no added fields)
```

**Notes:**
- `AxisSlot` adds nothing over `BindingSlot` — it exists purely so `AxisElement`'s field has a name
  that matches the symmetry of `ButtonSlot`, rather than leaving a reader to wonder why Button gets
  a named subtype and Axis doesn't. Cost is one empty class declaration; benefit is removing a
  "wait, what is this?" moment for anyone reading the code cold.
- An unassigned slot (XML: `Device="{NoDevice}" Key=""`) is stored exactly as the file has it —
  nothing is omitted or filtered out, since the user must be able to assign a key to a currently
  empty slot. `BindingSlot` adds:
  - `NO_DEVICE` — a named constant for the literal `"{NoDevice}"` placeholder string, so it's
    written once instead of retyped at every call site that needs to check for it.
  - `isAssigned(): boolean` — a derived helper (`device != NO_DEVICE`) so other code asks the slot
    a plain yes/no question instead of each independently comparing against the raw placeholder
    text. Purely additive — the raw `device`/`key` values are still readable unchanged, needed for
    accurate round-tripping back to the file.
- **Critical execution-layer caveat, confirmed 2026-06-23 (full detail in Punch List Section A):**
  `key`/`modifiers` must keep storing the raw XML values exactly as written, unchanged — that's
  still correct for accurate round-tripping/editing. But Frontier's own capture process does not
  treat `<Primary>`/`<Modifier>` as semantically meaningful labels — it's confirmed, reproducible
  behavior that a genuine modifier key (e.g. `LeftControl`) can end up written as the slot's `key`,
  while a genuine action key (e.g. `Y`) ends up written as a `<Modifier>`. **Any future BindForge
  code that *executes* a binding (sends real keystrokes) must classify each key by its own
  identity — `Alt`/`Ctrl`/`Shift` are always held, anything else is always tapped — rather than
  trusting `BindingSlot.key` to actually be the key to tap or `BindingSlot.modifiers` to actually
  be the keys to hold.** This is a parsing/execution-layer responsibility layered on top of this
  model, not something `BindingSlot`'s field meanings should try to encode — the model's job is
  still just to hold the raw values faithfully.
  - **Confirmed implemented, 2026-06-24:** `KeyBindingExecutor.normalizeChord()` in the existing
    codebase already does exactly this normalization (pools all keys, classifies by identity,
    holds modifiers, taps the one remaining trigger). Any future BindForge execution code should
    reuse this pattern rather than re-deriving it.
- Independently arrived at by EliteChroma's `EliteFiles` library (C#) via its own
  `DeviceKeyBase`/`DeviceKey`/`DeviceKeyCombination` split and `Undefined` sentinel — convergent
  design from a separate team solving the identical modeling problem.

## BindingElement hierarchy

Represents one whole action/setting (one top-level node under the `.binds` root — e.g.
`ToggleCargoScoop`, `PitchAxisRaw`, `MouseSensitivity`). Inheritance, not nullable fields, per this
doc's stated Design Principle — AXIS-only/BUTTON-only properties can't be assigned to the wrong
type at compile time.

```
BindingElement (abstract)
├── name: String          // XML element tag name, verbatim — confirmed sufficient as Purpose Mode's reference key too (Punch List E.6, resolved)
├── gameMode: GameMode    // GeneralControls / ShipControls / SrvControls / OnFootControls
└── subGroup: String      // e.g. "Flight Rotation" — per BindForge_GameMode_SubGroups.md

ButtonElement extends BindingElement
├── primary: ButtonSlot
├── secondary: ButtonSlot
└── toggleOn: boolean     // element-level — covers both slots as one shared behavior, BUTTON-only

AxisElement extends BindingElement
├── binding: AxisSlot     // the single <Binding> slot
├── inverted: boolean
└── deadzone: double

StandaloneSettingElement extends BindingElement
└── value: String         // bare Value= attribute, stored raw (numeric/enum-string/empty all as-is)
```

**Notes:**
- Duplicate element names (e.g. `MouseGUI` ×2, confirmed in `AUDIT_v2_FULL.md`) are handled at the
  `BindingProfile.bindings: List<BindingElement>` level — a `List`, not a `Map`, specifically so two
  entries with the same `name` can coexist without collision.
- A `.binds` file's element *names* are not reliable type indicators (`*ButtonPartial` elements
  classify as AXIS per the audit) — the parser determines `BindingElement` subtype from XML
  structure (presence of `<Binding>` vs `<Primary>`/`<Secondary>` vs bare `Value=`), never from the
  element name string.

---

## DeviceEntry (from DeviceMappings.xml)

Represents one device — one child element of `DeviceMappings.xml`'s `<Root>`. Tag name is the
friendly name shared everywhere else (`.binds`' `Device="..."` attribute, `.buttonMap` filenames).

```
DeviceEntry
├── name: String                       // tag name, e.g. "RVWAP"
├── primary: DeviceIdentifier          // the device's main <PID>/<VID>
└── alternatives: List<DeviceIdentifier>  // zero or more <Alternative> blocks, same shape as primary

DeviceIdentifier
├── productId: String                  // <PID>, hex string
├── vendorId: String                   // <VID>, hex string
└── supportsIcons: String (nullable)   // optional <SupportsIcons> — built-in icon-set name
```

**Notes:**
- `primary` and each `Alternative` share the exact same shape (PID/VID/optional icon-set name), so
  `DeviceIdentifier` is reused for both rather than duplicating those three fields twice — same DRY
  move as `BindingSlot`/`ButtonSlot`.
- PID/VID hex strings are inconsistently cased in real files (`045E` vs `045e`) — comparisons must
  be case-insensitive; stored value is kept as-read for round-tripping, not normalized.
- Confirmed against real `DeviceMappings.xml` (2026-06-20): no XML declaration in that file, and at
  least one HTML comment present inside `<Root>` — parser must tolerate both, not assume either is
  absent.

## buttonLabels (from `.buttonMap` files)

No dedicated class — `BindingProfile.buttonLabels: Map<String, Map<String, String>>` (outer key:
device friendly name; inner key: base Frontier name e.g. `"Joy_1"`; value: raw label text). A
`ButtonLabel` wrapper class was considered and rejected: its only field beyond the label text would
be the same key already used to store it in the map, which is the redundancy DRY argues against —
there's no symmetry argument pulling the other way here the way there was for `AxisSlot`.

A label's raw text is either free-form (`"T1 - Down"`) or a bracketed reference to a built-in icon
(`"[x360A]"`, per the fixed catalog in `DeviceButtonMaps/Readme.txt`). A static helper —
`isIconReference(String label)`, checking for the `[...]` bracket pattern — answers that distinction
without every caller re-deriving it, same pattern as `BindingSlot.isAssigned()`.

**Notes:**
- Confirmed against real `.buttonMap` samples (2026-06-20): no duplicate keys observed within a
  single file, and document order doesn't follow button-number order — nothing should assume
  either. One sample had no XML declaration, another did — same tolerance requirement as
  `DeviceMappings.xml`.
- The `.buttonMap` filename itself (minus extension) is the device's friendly name — the same join
  key as `DeviceEntry.name` and `.binds`' `Device="..."` attribute.

---

*Still to design: `BindingModifier` (confirm reuse as-is vs. any extension); the actual
extended-parser/writer method shapes (Punch List E.5).*
