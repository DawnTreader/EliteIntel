# Elite Dangerous Key Bindings — Definitive Reference

**Scope:** How Frontier's `.binds` file format and the game's own binding behavior actually
work — schema, quirks, bugs, and pitfalls. This is about the *game and the file*, not
EliteIntel's own code — for what EliteIntel's existing implementation currently does or
doesn't support, see `EXISTING_BINDFORGE_AUDIT.md` and `EXISTING_BINDING_MODEL_AUDIT.md`. For
the Java class design EliteIntel plans to build, see `BindForge_Data_Model.md`. This document
is the authoritative source of truth for *rules*; those other documents are about
*implementation status*.

**Sources consolidated here:** `AUDIT_v2_FULL.md` (full structural extraction of a real
`.binds` file), `BindForge_Punch_List.md` Section A, `BINDFORGE_SUBGROUPS_AUDIT.md` (in-game UI
vs. file cross-check), `BINDINGS_ANALYSIS.md` (controller/device-identity findings), and live
in-game testing by Krondor and DawnTreader (2026-06-23, full transcript preserved in
`BindForge_Punch_List.md` Section A for traceability).

---

## 1. The Three Element Types

Every top-level child of a `.binds` file's root is one of three structurally distinct shapes.
**Element names are not reliable type indicators** — e.g. elements named `*ButtonPartial`
actually classify as AXIS by structure, not by name. Type must be determined by which child
elements/attributes are actually present, never by the tag name.

### BUTTON
```xml
<YawLeftButton>
    <Primary Device="Keyboard" Key="Key_A" />
    <Secondary Device="{NoDevice}" Key="" />
</YawLeftButton>
```
- `<Primary>` and/or `<Secondary>` child elements, each with `Device`/`Key` attributes.
- Optional element-wide `<ToggleOn Value="0|1"/>` (covers both Primary and Secondary as one
  shared behavior — not per-slot).
- 268 of 457 elements in the reference specimen file (`DualVirpilDawnTreader.4.2.binds`).

### AXIS
```xml
<YawAxisRaw>
    <Binding Device="SaitekX52" Key="Joy_RZAxis" />
    <Inverted Value="0" />
    <Deadzone Value="0.00000000" />
</YawAxisRaw>
```
- A single `<Binding>` child (not `<Primary>`/`<Secondary>`), plus optional `<Inverted>` and
  `<Deadzone>` (range 0–1).
- 72 of 457 elements in the reference specimen.

### STANDALONE SETTING
```xml
<MouseSensitivity Value="1.00000000" />
```
- A bare leaf element, no children at all — just a `Value=` attribute on the element itself.
- 117 of 457 elements in the reference specimen. Covers sliders, dropdowns, and toggles that
  have no key-binding slot (mouse sensitivity, deadzone curves, menu-group toggles, etc.).

---

## 2. Slot-Level Properties (apply to `Primary`/`Secondary`/`Binding`)

### `Modifier` — zero to three per slot, hard-capped by the game itself

```xml
<Primary Device="Keyboard" Key="Key_I">
    <Modifier Device="Keyboard" Key="Key_LeftControl"/>
    <Modifier Device="Keyboard" Key="Key_LeftAlt"/>
    <Modifier Device="Keyboard" Key="Key_LeftShift"/>
</Primary>
```

- **Hard cap of 3 modifiers, confirmed by live testing.** A bind cannot carry more than three
  `<Modifier>` children. `LeftCtrl+LeftAlt+LeftShift+Y` (4 inputs) does **not** produce a
  4-modifier bind — the game always treats the 3-cap as absolute.
- **Modifier can be any device type, not just keyboard** — confirmed via `AutoBreakBuggyButton`
  (RVWAP joystick button used as a modifier) and the Virpil slider end-of-travel quirk.
- **Left/Right are tracked as distinct values**, freely mixable (`LeftShift` + `RightShift` on
  the same slot is valid).
- **Same key cannot be both the base key and a modifier on one slot.**

#### The actual mechanism behind the 3-cap (confirmed by live testing, 2026-06-23)

This is the single most important "pitfall" in the whole format. **Frontier's `<Primary>` vs.
`<Modifier>` XML labels are not semantically validated by the game at all.** The game does not
know or care which physical keys are "really" modifiers (`Ctrl`/`Alt`/`Shift`) versus base
keys — **any key can syntactically end up inside a `<Modifier>` element**, and a genuine
modifier key can end up as the slot's `Key=` attribute.

Reproduced directly: holding `LeftCtrl + LeftShift + LeftAlt + Y` (4 inputs total) during
capture produced:

```xml
<YawLeftButton_Landing>
    <Primary Device="Keyboard" Key="Key_LeftControl">
        <Modifier Device="Keyboard" Key="Key_LeftShift" />
        <Modifier Device="Keyboard" Key="Key_LeftAlt" />
        <Modifier Device="Keyboard" Key="Key_Y" />
    </Primary>
    <Secondary Device="Keyboard" Key="Key_Tab">
        <Modifier Device="Keyboard" Key="Key_LeftShift" />
    </Secondary>
</YawLeftButton_Landing>
```

`Key_Y` — a genuine action key — ended up written as a `<Modifier>`. `Key_LeftControl` — a
genuine modifier key — ended up written as the Primary `Key=`.

**The actual rule: whatever is held down when the *last* key is pressed becomes the
`<Modifier>` set (in whatever order); the last-pressed key becomes the slot's `Key=`
attribute.** It is purely a function of **press order**, not key identity. Practical
consequence for any capture UI: hold the intended modifiers down first, then press the
intended base key last — that produces the expected result. (EliteIntel's own shipped capture
dialog already does this correctly — it accumulates modifiers in press order and finalizes on
the first non-modifier key, which matches the game's own behavior.)

**Modifier order inside the XML does not affect the game's own conflict recognition.**
Confirmed via the in-game "Are you sure?" rebind-conflict dialog: two captures of the same key
set, captured in different left/right orderings, are correctly recognized by the game as the
same combo regardless of order.

#### The execution bug this causes (confirmed root cause, real fix in progress)

Because the game treats a chord as an **unordered set of held keys** rather than trusting the
`<Primary>`/`<Modifier>` labels, any *external* tool that takes those XML labels at face value
— tapping whatever's in `Primary`, holding whatever's in `Modifier` — will get the hold/tap
roles backwards whenever Frontier's capture happened to put a real action key into a `Modifier`
slot (as shown above). Two concrete, confirmed failure modes result:

- **Long-press of the actual action key fires unintentionally.** If that key is now sitting in
  a `<Modifier>` slot, a naive executor holds it for the full chord duration (150ms+) — enough
  to trigger that key's own separate `Hold`-style binding elsewhere, if it has one.
- **A transient held-key subset can spuriously match a completely different binding.** While
  the (mislabeled) modifiers are held but the (mislabeled) Primary hasn't been tapped yet, the
  currently-held set can be an exact match for some *other* binding's full combo, firing that
  one too.

**The fix is not "the primary can never be a modifier" — it's "never trust the slot labels for
execution."** Any code that needs to actually press keys to fire a binding must classify each
key by its own identity, independent of which XML element it's nested in: `Alt`/`Ctrl`/`Shift`
(any left/right variant) are always held; anything else is always tapped, regardless of XML
position.

**Confirmed implemented in EliteIntel itself, 2026-06-24.** `KeyBindingExecutor.normalizeChord()`
pools every key from both slots into a set, classifies each by identity
(`isModifierKey()` — is this specific token one of the six supported Ctrl/Shift/Alt variants,
regardless of which XML element it came from), holds the modifiers, and taps the single
remaining non-modifier key. Two edge cases are explicitly logged rather than silently
mis-executed: every key in the chord turning out to be a modifier (nothing to tap), and two or
more non-modifier keys in one chord (ambiguous — the slot-labelled primary is preferred if it
qualifies). Any future BindForge execution code can reuse this exact pattern rather than
re-deriving it.

### `Hold` — per-slot, capture-method artifact

```xml
<Primary Device="Keyboard" Key="Key_Y">
    <Hold Value="1" />
</Primary>
```

- Boolean-ish (`Value="1"` observed). Origin is *how the key was captured* — held ~1+ second
  during detection vs. tapped — not a user-chosen setting exposed anywhere obvious.
- Never appears on AXIS.
- Independent of, and can coexist with, `ToggleOn` on the same element (confirmed via
  `ToggleCargoScoop`).
- **UI display inconsistency, confirmed 2026-06-23:** `Hold` does not appear in the game's
  right-hand "About this setting" explanation panel, but it does show directly in the bind
  slot's own display. The two panels can show inconsistent information for the same binding.
- **A theory once suspected here — that one binding's key set being a subset of another's
  causes silent suppression — was investigated further and retracted. See §2.5.**

### 2.5 The subset-suppression theory — investigated, retracted, 2026-06-24

An earlier round of testing (2026-06-23) reproduced a case that looked exactly like
subset-key-set suppression: `LeftCtrl+LeftShift+LeftAlt+I` (assigned to `GalaxyMapOpen`) fired
correctly, while the structurally identical `LeftCtrl+LeftShift+LeftAlt+Y` (also assigned to
`GalaxyMapOpen`, with `Y` alone separately bound to `HeadLookReset`) silently failed to fire.
The working theory at the time was that Elite matches a binding whenever *all* its keys are
held (not requiring "and nothing else"), making `{Y}` a subset of `{Ctrl,Shift,Alt,Y}`, and
that the engine statically suppresses the more specific (longer) binding whenever this overlap
exists anywhere in the same context.

**Retracted.** Further in-game testing did not reproduce this as a real conflict — the original
failure was traced to a **stale `.binds` reload**: Elite had not yet re-read the file (see the
lifecycle note below), so the test was observing an old binding state, not a genuine
subset-suppression rule. The actual, now-confirmed matching model is the opposite of the
retracted theory:

> **Elite matches a binding by its exact chord — the main key plus exactly its modifier set.
> Holding extra modifiers does not trigger a binding that has fewer of them.** A bare key
> (e.g. `HeadLookReset = Key_Y`) and a modified chord on that same key (e.g.
> `Ctrl+Shift+Alt+Y`) are two distinct chords that **both fire correctly, independently** — they
> do not conflict, and neither suppresses the other. Two bindings only conflict when they share
> the **identical** key set, within the same active context.

**New, genuinely useful lifecycle pitfall this surfaced:** Elite only re-reads its `.binds`
file when its own in-game Controls screen is opened — editing the file externally (by hand or
via any tool) does not take effect immediately in a running game session. Any testing
methodology, including this kind of root-cause investigation, must account for this or it will
misattribute a stale-read symptom to a real binding-logic bug, exactly as happened here.

### `ToggleOn` — element-level, BUTTON-only

- Covers both Primary and Secondary as **one shared behavior** — it is not a per-slot property,
  unlike `Modifier`/`Hold`.
- Only two observed values (`0`/`1`).
- Never appears on AXIS.

### `Deadzone` / `Inverted` — AXIS-only

- `Deadzone`: range 0–1, never appears on BUTTON.
- `Inverted`: boolean-ish, never appears on BUTTON.

---

## 3. File-Level Structural Quirks (confirmed via full-file extraction)

- **Duplicate element names are possible** (e.g. `MouseGUI` appears twice in the reference
  specimen). The `.binds` schema itself tolerates this; nothing in the file format prevents it.
- **Half-axis/mouse-wheel key strings can appear on BUTTON slots, not just AXIS** — e.g.
  `Neg_Joy_YAxis`, `Pos_Mouse_ZAxis` as a BUTTON slot's `Key=` value. These are not exclusive to
  AXIS elements.
- **Named devices beyond the obvious Keyboard/Mouse/RVWAP exist** — T-Rudder, LVWAP, vJoy, and
  others. A device field must accept arbitrary names; there's no fixed enum of valid devices.
- **One non-conforming node exists**: `KeyboardLayout` is stored as **text content**, not an
  attribute — structurally different from every other element in the file and needs its own
  handling path.
- **Controller/joystick device identity is opaque.** A non-keyboard `Device=` value is an
  8-hex-character string (e.g. `"045E028E"`) derived from the device's hardware GUID
  (VID/PID-based) — the file format itself does not decode or expose VID/PID/device-type
  separately. There is no way, from the `.binds` file alone, to know what kind of device that
  hex string refers to (joystick vs. gamepad vs. wheel) — that distinction simply isn't carried
  by the format.
- **Button/axis/POV key tokens are unstructured strings, not a parsed shape.** `Joy_1`,
  `Joy_POV1Up`, `XAxis`, `Slider1`, etc. are all just raw token strings — the format has no
  structured `{kind: BUTTON|AXIS|POV, index, direction}` representation; everything is a string
  to be pattern-matched by convention, not validated by the schema.

---

## 4. File Locations & Lifecycle

- **`.binds`/`StartPreset.*.start` files live in exactly one canonical, storefront-agnostic
  location:** `%LOCALAPPDATA%\Frontier Developments\Elite Dangerous\Options\Bindings\` —
  shared across every storefront install (Steam, Epic, Frontier Direct, Oculus) on the same
  Windows account. No multiplicity here.
- **`DeviceMappings.xml` and `.buttonMap` files are cosmetic-only (button labeling), not game
  config** — but unlike `.binds`, they are genuinely duplicated **per storefront install**,
  each under that storefront's own `[GameInstall]\Products\<product-name>\ControlSchemes\`
  folder. Confirmed real on a real multi-storefront machine (Epic + Steam side by side, each
  with its own independent copy).
- **The `.#.0` version suffix on a `.binds`/`StartPreset.#.start` filename is assigned by the
  game itself**, tied to the game's own version (e.g. Odyssey's on-foot component bump produced
  a `.4.0` suffix). No external tool mints this number — it only ever reacts to whatever suffix
  the game already wrote.
- **A game update *can* overwrite/erase `.binds`, `StartPreset.*.start`, `DeviceMappings.xml`,
  and `.buttonMap` files — but this is not universal or guaranteed.** It doesn't happen on
  every update, and not for every user. Any tool managing these files must always validate
  they're present and correct after an update rather than assuming either outcome.
- **`DeviceMappings.xml`/`.buttonMap` only work inside the game's own install folder** — placing
  them in the user-config bindings folder does not work; Frontier specifically reads them from
  the install-folder `ControlSchemes` location.

---

## 5. In-Game UI vs. the Actual File — Where Frontier's Own UI Lies to You

Cross-referencing the in-game Control Bindings UI's displayed action list against the real
`.binds` file (`BINDFORGE_SUBGROUPS_AUDIT.md`) found several real discrepancies — Frontier's
own settings screen does not faithfully represent everything the underlying file supports:

- **`UI_Select`** ("Confirm/Select" in panel UIs) is bound and functional
  (Keyboard `Space` + RVWAP `Joy_4` in the reference profile) but **does not appear anywhere**
  in the in-game UI's Interface Mode section.
- **`HumanoidPing`** (on-foot "Ping/Call Out") exists as a real, bindable element in the file
  but is **completely absent from the in-game UI** in any on-foot section.
- **`MouseReset` and `BlockMouseDecay`** are shown in the in-game UI as toggle-only settings
  with "no standard key binding slots" — but the underlying XML **does** have real
  `<Primary>`/`<Secondary>` child elements for both (currently unbound). They are functionally
  bindable at the file level; the in-game UI simply doesn't expose the binding row, only the
  toggle.
- **`DeployHeatSink`** is bound and functional (Numpad_Divide in the reference profile) but
  doesn't appear under either the Cooling or Miscellaneous sections of the in-game UI where it
  logically belongs.
- One apparent doc/UI duplicate ("Select Next Target" appearing as a separate row from "Cycle
  Next Target") turned out to have **no corresponding XML element at all** — likely the same
  UI row transcribed twice, or a label that changed between game builds without the underlying
  action name changing.

**Practical implication:** the in-game Control Bindings screen cannot be trusted as a complete
inventory of what's bindable. Anything built against "what the game shows you" rather than
"what the `.binds` schema actually supports" will silently miss real, functional bindings.

---

## 6. Summary — The Core Pitfalls, in One List

1. Element *names* never indicate type — only structure does (`*ButtonPartial` elements are
   AXIS, not BUTTON).
2. The modifier cap (3) is enforced by **press order**, not key identity — and Frontier's own
   capture process can and does mislabel a real action key as a `<Modifier>` and a real
   modifier key as the `Key=` attribute. Anything that executes a binding by trusting those
   labels at face value will get hold/tap roles backwards. (EliteIntel's own execution layer now
   correctly normalizes by key identity instead — see §2.)
3. **Elite only re-reads `.binds` when its own in-game Controls screen is opened.** Editing the
   file externally takes no effect in a running game session until that screen is opened —
   skipping this step makes a real edit look like a no-op, or makes a stale read look like a
   genuine bug (this is exactly what produced the now-retracted subset-suppression theory in
   §2.5 — test methodology must account for it).
4. Elite's actual binding-match model is **exact-chord, not subset/priority-based**: a bare key
   and a modified chord on that same key are distinct and both fire independently, never
   suppressing each other. (A subset-suppression theory was suspected here and retracted after
   further testing — see §2.5.)
5. `Hold` can show inconsistently between the game's two display surfaces (the explanation
   panel vs. the slot display itself).
6. The in-game Control Bindings UI is not a complete or fully accurate inventory of the
   `.binds` schema — real, bindable elements exist that the UI never shows, and some UI rows
   don't correspond to any real XML element at all.
7. Controller/joystick device identity in the file is an opaque, undecodable hex string — no
   VID/PID/device-type distinction survives into the format.
8. `DeviceMappings.xml`/`.buttonMap` genuinely duplicate per storefront install; `.binds` does
   not — these two file families have different multiplicity rules, easy to get wrong.
9. Game updates can silently wipe the cosmetic files and occasionally the bindings themselves,
   with no guaranteed pattern to when this happens.

---

*Consolidated 2026-06-23 from prior audits and live in-game testing. Update this document as
new rules/pitfalls are confirmed — it should remain the single source of truth for "how
Frontier's binding system actually behaves," separate from EliteIntel's own implementation
status.*
