# SDL3 Keyboard Capture — Init/Window/Event-Loop Research

## TL;DR

SDL3 keyboard capture was never actually given what it needs to work — this was not SDL3
failing. There is a prior diagnostic report (`KEYBOARD_DEBUG.md`, 2026-06-18) that already
pinpointed the exact cause: `SDL_Init()` was called without `SDL_INIT_VIDEO`, so SDL never
created a video device/window and `SDL_GetKeyboardState()` permanently reported "nothing
pressed." That bug was never fixed. Instead, two days later, the entire keyboard-polling
experiment was deleted wholesale during an unrelated refactor (`34672d1a`, "extract
elite.intel.devices package for shared SDL3 input") that consolidated SDL3 usage into the
current `DeviceService`. Today's `DeviceService` only initializes `SDL_INIT_JOYSTICK |
SDL_INIT_GAMEPAD` — no video, no window, no keyboard polling at all. The "out of focus"
question (would it see keystrokes typed into Elite Dangerous's window) was never even
reached, because the in-focus case never worked first.

---

## 1. Current `SDL_Init()` call

`DeviceService.initSdl()` (`app/src/main/java/elite/intel/devices/DeviceService.java:181-202`):

```java
boolean ok = SDLInit.SDL_Init(SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD);
```

Only `SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD` are requested (imports at lines 23-24).
**`SDL_INIT_VIDEO` is not included.** `SDL_INIT_EVENTS` is implicitly added by SDL3 for any
subsystem init, but that's not the same thing — see Section 4.

Grepping the entire codebase for `SDL_INIT_VIDEO`, `SDL_CreateWindow`, `SDL_Window`,
`SDLVideo`, `SDL_INIT_KEYBOARD` returns **zero matches anywhere**, in any file, on this
branch.

## 2. Is an `SDL_Window` ever created?

No. Zero matches for `SDL_CreateWindow` / `SDL_Window` anywhere in the codebase. No SDL
window — hidden, headless, or visible — exists today. This is consistent with #1: without
`SDL_INIT_VIDEO`, SDL has no video backend and therefore can't create a window even if code
asked it to.

## 3. Is there an active SDL event-pump loop?

Partially. `DeviceService.pollLoop()` (`DeviceService.java:104-105`) calls
`SDLEvents.SDL_PumpEvents()` once per iteration of its ~60 Hz joystick poll loop. That's a
real, successful call. But:
- There is no `SDL_PollEvent`/`SDL_WaitEvent` anywhere in the codebase — nothing ever dequeues
  individual `SDL_Event` structs. The current code relies entirely on `SDL_PumpEvents()` +
  direct state queries (`SDL_GetJoystickAxis`, `SDL_GetJoystickButton`), which works fine for
  joystick/HID because that path reads hardware state directly and has no dependency on a
  video device.
- `SDL_PumpEvents()`'s keyboard/window-event branch is a no-op without a video device, so even
  this call is currently keyboard-irrelevant — it's only there for joystick/gamepad.

## 4. Was SDL3 ever actually wired for keyboard capture?

Yes — once, in a now-deleted predecessor class — and it was diagnosed as broken, then the
whole thing was removed rather than fixed.

**History**, via `git log`:

1. `520fa489` — StarVizion prototype: SDL3 overlay + controller input, in a class called
   `elite.intel.starvizion.input.SdlInputService`.
2. `b1d9ea40` — "StarVizion keyboard vizlet experiment" — added `pollKeyboard()`,
   `SDLKeyboard.SDL_GetKeyboardState()`, and `SvKeyPressedEvent` publication to
   `SdlInputService`, plus two new UI Vizlets (`KeyboardVizlet`, `CounterVizlet`) subscribed
   to that event.
3. **`KEYBOARD_DEBUG.md` (2026-06-18)** — diagnosed why the Counter Vizlet's count never
   incremented. Root cause, stated plainly in that report:

   > `SDL_Init()` is called without `SDL_INIT_VIDEO`, so SDL3 never creates a video device /
   > window and never pumps OS keyboard messages into its internal keyboard-state buffer.
   > `SDL_GetKeyboardState()` therefore reports every scancode as "not pressed", on every
   > poll, forever.

   That report also flagged, as an explicitly out-of-scope secondary question, whether
   keyboard state would even reflect keys typed into a *different* window (e.g. Elite
   Dangerous) once `SDL_INIT_VIDEO` was added — since SDL's default keyboard tracking is
   normally scoped to SDL's own focused window. It named the likely next steps if that path
   were pursued: a hidden/utility SDL window plus OS raw-input opt-in (Windows
   `RegisterRawInputDevices`/`RIDEV_INPUTSINK`, surfaced in SDL3 via a hint such as
   `SDL_HINT_WINDOWS_RAW_KEYBOARD`) on Windows, or XInput2 raw events on Linux.
4. **`34672d1a`** (2026-06-13, confirmed via `git merge-base --is-ancestor` to come *after* the
   `KEYBOARD_DEBUG.md` diagnostic commit in history, despite sharing a calendar date) —
   "extract elite.intel.devices package for shared SDL3 input." This refactor:
   - Renamed `SdlInputService.java` → `DeviceService.java` (60% similarity per git).
   - **Deleted** `pollKeyboard()`, the `SDLKeyboard.SDL_GetKeyboardState()` call, the
     `SDLKeyboard.SDL_GetModState()` call, the `SvKeyPressedEvent` import and publish call,
     and `buildKeyDisplayName()`'s `SDLKeyboard.SDL_GetScancodeName()` lookup — confirmed by
     diffing that commit directly.
   - Kept only the joystick/gamepad polling logic, which became today's `DeviceService`.
   - Commit message: "...removes the superseded starvizion Sv*/SdlInputService classes."

**Orphaned leftovers today:** `KeyboardVizlet` and `CounterVizlet`
(`app/src/main/java/elite/intel/starvizion/overlay/`) and `SvKeyPressedEvent`
(`app/src/main/java/elite/intel/starvizion/event/SvKeyPressedEvent.java`) all still exist and
still compile — `CounterVizlet.onKeyPressed(SvKeyPressedEvent)` is still `@Subscribe`d — but
nothing in the codebase publishes `SvKeyPressedEvent` anymore. These Vizlets are dead UI: they
display and subscribe correctly, but will never receive an event, because their only producer
was deleted in `34672d1a`.

## Bottom line

- SDL3 itself never failed at keyboard capture — it was never given a video device, so
  `SDL_GetKeyboardState()` had nothing to report by construction. This was caught and written
  up correctly in `KEYBOARD_DEBUG.md`.
- That diagnosed, fixable bug (`add SDL_INIT_VIDEO`) was never actually fixed. Instead the
  capability was deleted outright during a refactor whose stated purpose was consolidating
  joystick/HOTAS infrastructure, not evaluating or fixing keyboard capture.
- The harder question this whole investigation was leading toward — whether SDL3 can see
  keystrokes while a *different* window (Elite Dangerous) has OS focus — was never reached.
  Even the simpler in-focus case never worked, so "out of focus" was never tested at all. Per
  `KEYBOARD_DEBUG.md`, achieving that would need SDL3's raw-input opt-in hints/registration on
  top of `SDL_INIT_VIDEO`, which is real additional native-OS work, not something SDL3 gives
  for free once a window exists.
- For a design decision between "push further on SDL3's own keyboard path" vs. "use a
  separate OS-level mechanism" (as `WindowsNativeKeyInput`/`LinuxX11NativeKeyInput` already do
  for execution): SDL3's keyboard path has unresolved, nontrivial work ahead of it on both
  axes (video/window plumbing, then raw-input/global-focus plumbing on top), none of which is
  done today, versus a platform-native low-level keyboard hook (`SetWindowsHookEx`
  `WH_KEYBOARD_LL` on Windows; an XInput2/X11 global key-grab equivalent on Linux) which is a
  more directly proven pattern for "global, focus-independent key capture" and wouldn't
  require resurrecting or re-architecting any SDL3 state.
