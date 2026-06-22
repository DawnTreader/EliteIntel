# KeyCaptureMapper / Push-to-Talk / StarVizion — Read-Only Audit

## TL;DR

Krondor's premise is half right. Push-to-talk on this branch is **controller-only** — it
detects button presses through `DeviceService`'s existing SDL3 joystick/gamepad poll loop
(`SDL_GetJoystickButton`), the same shared infrastructure documented in
`elite.intel.devices.PACKAGE.md`. It does **not** do any keyboard capture at all, so there is no
push-to-talk "keyboard detection" code path to pull from — the gap isn't a hidden mechanism,
it's that push-to-talk simply never needed keyboard capture and never built any.
`KeyCaptureMapper` today is pure data conversion (`KeyEvent` → Elite token); it has zero
callers and does no capturing of its own — an SDL3 keyboard-capture rework would need a new
capture front-end (an SDL3 window + `SDL_INIT_VIDEO`, per `2026-06-20_SDL3_Research.md`) feeding
into it, not a refactor of `KeyCaptureMapper` itself. Separately, StarVizion is **almost**
fully isolated and removable: the dependency graph is one-way (StarVizion consumes
`devices`/`eventbus` events; nothing outside StarVizion references StarVizion internals except
a single `AppView` wiring point) — and that wiring point isn't even live, since
`StarVizionTabPanel` is constructed and disposed by `AppView` but **never added to the tab
pane**. Removing it is safe; nothing breaks.

---

## 1. How push-to-talk actually detects its trigger today

**It's controller-button-based, full stop — no keyboard path exists.**

The button press itself is detected inside `DeviceService.pollJoystick()`
(`app/src/main/java/elite/intel/devices/DeviceService.java:255-276`), which is the same SDL3
joystick/gamepad poll loop documented in `2026-06-20_SDL3_Research.md`:

```java
// DeviceService.java:269-275
for (int b = 0; b < prevBtn.length; b++) {
    boolean pressed = SDLJoystick.SDL_GetJoystickButton(handle, b);
    if (pressed != prevBtn[b]) {
        prevBtn[b] = pressed;
        DeviceBus.publish(new DeviceButtonEvent(id, b, pressed));
    }
}
```

This loop runs inside `DeviceService.pollLoop()` (`DeviceService.java:95-141`), which calls
`initSdl()` (`DeviceService.java:181-202`) — confirmed identical to the prior research:
`SDL_Init(SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD)` only, no `SDL_INIT_VIDEO`, no window. There is
no separate SDL/AWT/JNA path specifically for push-to-talk — it rides the exact same
joystick/gamepad event stream as every other controller consumer in the app
(`elite/intel/devices/PACKAGE.md:3`: "shared infrastructure — StarVizion, BindForge, and
push-to-talk all consume these events").

`DeviceButtonEvent` is consumed by `InputSettingsPanel.onButtonState()`
(`app/src/main/java/elite/intel/ui/screen/settings/InputSettingsPanel.java:319-344`), which is
where the actual push-to-talk semantics (toggle vs. hold mode) live:

```java
// InputSettingsPanel.java:319-344
@Subscribe
public void onButtonState(DeviceButtonEvent event) {
    if (!pushToTalkEnabled) return;

    Device device = selectedDevice;
    int buttonIndex = selectedButtonIndex;
    if (device == null || buttonIndex < 0) return;
    if (event.deviceId() != device.id() || event.buttonIndex() != buttonIndex) return;

    if (toggleMode) {
        if (event.pressed()) {
            AudioPlayer.getInstance().playBeep(AudioPlayer.BEEP_2);
            GameEventBus.publish(new TTSInterruptEvent(true));
            toggleSleepWake();
        }
    } else {
        if (event.pressed()) {
            AudioPlayer.getInstance().playBeep(AudioPlayer.BEEP_2);
            GameEventBus.publish(new TTSInterruptEvent(true));
            UiBus.publish(new PttButtonStateEvent(true));
        } else {
            AudioPlayer.getInstance().playBeep(AudioPlayer.BEEP_1);
            UiBus.publish(new PttButtonStateEvent(false));
        }
    }
}
```

That class's own doc comment confirms the design intent explicitly
(`InputSettingsPanel.java:33-36`):

> "Input" settings tab - lets the user map a controller button to push-to-talk, monitored via
> the shared SDL3 poll loop in `{@link DeviceService}`.

`PttButtonStateEvent` is then consumed by the STT layer —
`ParakeetSTTImpl.onPttButtonState()` (`app/src/main/java/elite/intel/ai/ears/parakeet/ParakeetSTTImpl.java:526`)
— to gate the mic, which is outside the scope of "how is the trigger detected" but closes the
loop for completeness.

**No AWT `KeyListener`, no JNA keyboard hook, no SDL3 keyboard polling anywhere in this chain.**
Grepping the whole codebase for `Ptt`/`PushToTalk` confirms `DeviceService.java` is not even in
the result set for any keyboard-specific API — its only relevant export to push-to-talk is the
generic `DeviceButtonEvent` from the joystick/gamepad loop above.

**Conclusion for Krondor's framing:** there is no gap between "what push-to-talk does" and
"what `DeviceService` provides" — they match exactly (joystick/gamepad only). The gap is between
Krondor's description ("push-to-talk added device-capture routines we should pull from for
*keyboard*") and reality: push-to-talk added zero keyboard-capture code, because it only ever
needed a controller button. There is nothing keyboard-related to extract from push-to-talk —
an SDL3 keyboard-capture entry point for `KeyCaptureMapper` would be new work, not a port of
existing logic.

---

## 2. `KeyCaptureMapper.java` — current responsibility and what SDL3 rework would require

**File:** `app/src/main/java/elite/intel/util/KeyCaptureMapper.java` (312 lines). **Zero
callers** anywhere in the codebase today (confirmed by grep — the only matches are the class's
own declaration/constructor and two report mentions describing it as future/planned
scaffolding). Added in `53818119` ("KeyCaptureMapper for use in UI / Key Bindings builder"),
Linux support added later in `76f1625a`.

### What it actually does today

It is a **pure, stateless converter** — `KeyEvent → Optional<String>` Elite-token — with no
capture, no listening, no event loop, and no UI of its own:

- `fromKeyEvent(KeyEvent e)` (`:173-175`) is the only public entry point besides
  `isModifierOnly()` (`:187-192`, distinguishes bare Shift/Ctrl/Alt presses).
- `resolveToken()` (`:194-203`) special-cases left/right Shift, Ctrl, Alt by
  `e.getKeyLocation()`, then defers everything else to `resolveLayoutAware()`.
- `resolveLayoutAware()` (`:216-239`) does the actual layout-independence trick: on Windows it
  calls `WindowsScanResolver.getScanCode(vk)` (`:305-311`, JNA `User32.MapVirtualKeyEx`) to turn
  a VK code into a PS/2 scan code, looks that up in `SCAN_TO_TOKEN` (`:40, 49-98`); on Linux it
  does the analogous thing via `LinuxScanResolver` (`:242-284`, JNA X11
  `XKeysymToKeycode`/`XOpenDisplay`). If either native call is unavailable or returns nothing
  (caught broadly via `catch (Throwable ignored)`, `:224-226` and `:234-236`), it falls back to
  the QWERTY-assumption `VK_TO_TOKEN` map (`:44, 100-160`).
- It assumes the caller has *already* captured a `java.awt.event.KeyEvent` — there's no
  `KeyListener`, no SDL hook, no polling loop inside this class. It is invoked, not invoking.

In short: today it answers "given this keypress, what's its layout-independent Elite token?" —
it has no opinion about *how* the keypress was captured, and per the codebase-wide grep, nothing
currently calls it, so it's inert scaffolding exactly as Krondor described.

### What would need to change for SDL3-driven live capture

To make this class (or a new class wrapping it) the actual SDL3 keyboard-capture entry point,
the capture side has to be built from scratch — it doesn't exist anywhere in the codebase yet,
per `2026-06-20_SDL3_Research.md`'s findings, which this audit reconfirms:

1. **A real SDL3 keyboard capture surface must be created.** `DeviceService.initSdl()`
   (`DeviceService.java:185`) only requests `SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD` — no
   `SDL_INIT_VIDEO`, no `SDL_Window`, anywhere in the codebase (confirmed again: zero matches
   for `SDL_CreateWindow`/`SDL_Window`/`SDL_INIT_VIDEO`). `SDL_GetKeyboardState()` cannot report
   anything without a video device backing it. This was the exact, already-diagnosed root cause
   in `KEYBOARD_DEBUG.md`, and it is still true today — adding `SDL_INIT_VIDEO` plus a (likely
   hidden/utility) `SDL_Window` is a prerequisite, not optional.
2. **`KeyCaptureMapper`'s input type would need to change from `KeyEvent` to a scancode-bearing
   SDL3 type.** Right now the entire class is keyed off `java.awt.event.KeyEvent` (`VK_*`
   constants, `getKeyLocation()`). An SDL3 capture loop produces `SDL_Scancode` values directly
   from `SDL_GetKeyboardState()`/`SDL_PollEvent` — at that point `SCAN_TO_TOKEN` (which is
   already scan-code-keyed) becomes directly usable, but `VK_TO_TOKEN` and both
   `*ScanResolver` inner classes (whose entire job is reverse-engineering a scan code *from* a
   VK code via JNA) become **dead code** for the SDL3 path, since SDL3 hands you the scan code
   natively — no `MapVirtualKeyEx`/`XKeysymToKeycode` round-trip needed. Per the project's DRY
   principle, the class would need a new `fromScancode(int sdlScancode)`-shaped entry point
   rather than retrofitting `fromKeyEvent`, since the two capture sources don't share a type.
3. **A capture-mode/window-focus story is still the open question flagged in the SDL3 report**
   — whether `SDL_GetKeyboardState()` sees keys typed while a different window (e.g. Elite
   Dangerous, or even just outside whatever capture window BindForge pops up) has OS focus is
   *unresolved*, needing the raw-input opt-in hints (`SDL_HINT_WINDOWS_RAW_KEYBOARD` on Windows,
   XInput2 on Linux) called out there. For a bind-capture dialog this may matter less than for
   global push-to-talk (the user is expected to be focused on EliteIntel's own capture dialog
   while pressing the key), but it should be confirmed rather than assumed.
4. **Lifecycle/ownership**: `DeviceService` is a long-lived singleton background service. A
   keyboard-capture window for BindForge is the opposite — transient, opened only while the user
   is actively rebinding a key. These shouldn't share a class; `KeyCaptureMapper` (or a new
   sibling class) would own a short-lived SDL3 window/poll loop scoped to "capture dialog open,"
   not extend `DeviceService`'s always-on poll loop.

**Bottom line for this question:** `KeyCaptureMapper` is reusable as the *back half* (scancode
→ Elite token) of the new design with one shape change (VK-based input → scancode-based input,
dropping the now-redundant `*ScanResolver` JNA detours for the SDL3 path). The *front half*
(an actual SDL3 keyboard-capture surface) doesn't exist anywhere in this codebase and isn't
hiding in push-to-talk or `DeviceService` — it has to be built new, starting with the
`SDL_INIT_VIDEO` fix `KEYBOARD_DEBUG.md` already specified.

---

## 3. StarVizion — is it isolated enough to remove safely?

**Package:** `app/src/main/java/elite/intel/starvizion/` — 13 files: `StarVizionTabPanel.java`,
`StarVizionPalette.java`, `event/SvKeyPressedEvent.java`, `model/SvAxis.java`,
`model/SvButton.java`, `overlay/{AxesVizlet,AxesSettingsDialog,ButtonVizlet,
ButtonSettingsDialog,CounterVizlet,KeyboardVizlet,KeyboardSettingsDialog,VizletWindow}.java`.

### Inbound references (outside StarVizion → into StarVizion)

Exactly **one**, a single class in a single file:

- `app/src/main/java/elite/intel/ui/screen/AppView.java:6` — `import elite.intel.starvizion.StarVizionTabPanel;`
- `AppView.java:50` — field declaration `private StarVizionTabPanel starVizionTabPanel;`
- `AppView.java:112` — `starVizionTabPanel = new StarVizionTabPanel();`
- `AppView.java:180` — `if (starVizionTabPanel != null) starVizionTabPanel.dispose();`

**That's the entire inbound surface — and it's not even fully wired.** `AppView` builds six
other tab panels (`AiTabPanel`, `CommanderTabPanel`, `ActionsTabPanel`, `BindForgeTabPanel`,
`SettingsTabPanel`, `UsageStatsTabPanel`, plus two `MarkdownViewPanel`s) and adds every one of
them via `tabs.addTab(...)` (`AppView.java:114-120`) — **except** `starVizionTabPanel`, which is
constructed and later disposed but never passed to `tabs.addTab()`. There is no
`tabs.addTab(..., starVizionTabPanel)` call anywhere in the file. The `tab.starvizion` i18n key
(`gui.properties:15`) exists but is likewise never referenced from any `.java` file. **StarVizion
is already unreachable from the running UI on this branch** — the only live effect of
`StarVizionTabPanel` existing today is that its constructor calls `DeviceBus.register(this)`
(`StarVizionTabPanel.java:47`), so it sits on the bus listening for `DeviceServiceStateEvent` it
can never display, until `AppView.java:180` unregisters it at shutdown.

### Outbound references (StarVizion → outside StarVizion)

One-way consumption of shared infrastructure only — StarVizion never gets referenced back by
what it depends on:

| StarVizion file | Imports from outside the package |
|---|---|
| `StarVizionTabPanel.java:4-6` | `devices.DeviceService`, `devices.events.DeviceServiceStateEvent`, `eventbus.DeviceBus` |
| `overlay/VizletWindow.java:3-4` | `eventbus.DeviceBus`, `eventbus.GameEventBus` |
| `overlay/ButtonSettingsDialog.java:3-4,7-8` | `devices.DeviceService`, `devices.model.Device`, `ui.theme.AppTheme`, `ui.theme.HudForms` |
| `overlay/AxesSettingsDialog.java:3-4,7-8` | `devices.DeviceService`, `devices.model.Device`, `ui.theme.AppTheme`, `ui.theme.HudForms` |
| `overlay/AxesVizlet.java:4-6` | `devices.events.DeviceAxisEvent`, `devices.events.DeviceDisconnectedEvent`, `devices.model.Device` |
| `overlay/ButtonVizlet.java:4-6` | `devices.events.DeviceButtonEvent`, `devices.events.DeviceDisconnectedEvent`, `devices.model.Device` |
| `overlay/KeyboardSettingsDialog.java:3-4` | `ui.theme.AppTheme`, `ui.theme.HudForms` |

This matches `elite/intel/devices/PACKAGE.md:3` exactly: "shared infrastructure - StarVizion,
BindForge, and push-to-talk all consume these events rather than managing their own SDL3
context." `DeviceService`/`DeviceBus` have **no knowledge of StarVizion** — they publish generic
`DeviceConnectedEvent`/`DeviceDisconnectedEvent`/`DeviceAxisEvent`/`DeviceButtonEvent`/
`DeviceServiceStateEvent` onto `DeviceBus`, and StarVizion is just one of several subscribers
(alongside `InputSettingsPanel` for push-to-talk and presumably BindForge UI). Confirmed via
grep: `DeviceService.java` contains zero references to `starvizion`.

### Dead/orphaned leftovers inside StarVizion itself

Not load-bearing for anything outside the package, but worth flagging since they'd be deleted
along with everything else: `SvKeyPressedEvent` (`event/SvKeyPressedEvent.java`) has **no
publisher anywhere in the codebase** — `KeyboardVizlet.onKeyPressed()` (`overlay/KeyboardVizlet.java:45`)
and `CounterVizlet.onKeyPressed()` (`overlay/CounterVizlet.java:33`) both `@Subscribe` to an
event nothing ever fires, per the git history already documented in
`2026-06-20_SDL3_Research.md` (`pollKeyboard()` and its `SvKeyPressedEvent` publish call were
deleted from the predecessor class in `34672d1a`, but these two subscriber Vizlets were never
cleaned up alongside it).

### Conclusion

**StarVizion is isolated enough to remove safely, with no functional regression.** The
dependency graph is strictly one-way (StarVizion → `devices`/`eventbus`/`ui.theme`, never the
reverse), the single inbound touchpoint (`AppView.java:6,50,112,180`) is four lines of
construct/dispose wiring with no behavioral consequence today (the panel is never added to the
visible tab pane), and nothing in push-to-talk or BindForge imports anything from
`elite.intel.starvizion`. Removing the package requires only:

1. Deleting `app/src/main/java/elite/intel/starvizion/` (all 13 files).
2. Removing the 4 `AppView.java` lines listed above (import, field, construction, dispose call).
3. Removing the now-orphaned `tab.starvizion`/`starvizion.*`/`vizlet.menu.*` keys from
   `app/src/main/resources/i18n/gui*.properties` (cosmetic cleanup, not required for
   correctness since unreferenced properties are harmless).
4. Removing the StarVizion-specific `<Logger name="elite.intel.starvizion" .../>` entry in
   `app/src/main/resources/log4j2.xml:56` (harmless if left, since log4j2 simply won't match
   anything, but it'd be dead config).

**If kept instead of removed**, the one thing to fix is unrelated to dependencies: StarVizion is
currently dead code from a *user-visibility* standpoint (never added to the tab pane), which
looks like an accidental regression introduced at some point after `520fa489`'s prototype,
separate from whether it's architecturally safe to delete.
