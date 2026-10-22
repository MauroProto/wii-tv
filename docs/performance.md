# Menu frame scheduling

The menu stops scheduling its next frame when `WiiMenuActivity.onPause()` calls
`WiiMenuView.stop()`. Detaching the view cancels the frame, clock blink and intro
timeout callbacks too. A frame already submitted to Android may finish; it cannot
start another animation loop while the menu is stopped. Resuming refreshes the
clock immediately and restarts the existing animation cadence.

Navigation, intro fades and the pulsing continue prompt still use display frames.
Live banners keep their 33 ms delay. An idle menu waits for an event or its existing
one-second colon blink. The clock reads wall time at most once per second during
continuous rendering, instead of every 250 ms. At idle, draw timing can cause a
blink to fall short of that interval, deferring the read to the following frame;
there is no strict one-second bound on minute updates. Each read still obtains the
current calendar, so timezone changes are picked up without a persistent receiver
or a cached timezone.

## Evidence and limits

The original next-frame decision ignored the `started` flag. Two regression tests
failed with that decision: a paused menu scheduled frames when an animation or
live banner was active, and the pause/resume case did not stop scheduling.

`WiiMenuTimingTest` covers stopped state with every combination of moving, intro
and live content; display cadence; live-banner cadence; idle state; resumption;
clock refresh after resumption; and continuous clock checks. In a simulated
10-second sequence with a frame every 16 ms, the new clock decision requests
10 calendar reads. The previous 250 ms threshold would request 40. This is a
scheduler count, not a CPU, RAM, battery or frame-time measurement on Android.

Static review found existing reuse of paints, paths and shaders, per-channel
bitmap caching, a still-layer RenderNode, off-thread backdrop generation, music
streaming and lifecycle calls that stop music and release sounds. Those mechanisms
and the colour tables are unchanged. This change targets unnecessary scheduling
and calendar allocations; it does not establish a reduction in retained memory.

Run the same checks as CI from `launcher/`:

```sh
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

On Windows use
`.\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease`.
Use JDK 17, Android platform 34 and build tools 34.0.0. Tool tests run from the
repository root with `python3 -m unittest discover -s tests -v`; the existing shell
fixtures require a POSIX environment, such as Linux or WSL.

## Physical-device validation still needed

No Chromecast was connected, installed on or modified during validation. Android
TV 14 is covered by the existing SDK 34 build, not by a physical compatibility test.
Unit tests cover the scheduling decisions; callback delivery and GPU behaviour
still need Android runtime testing.

Before installation, keep the current APK and a recovery route. With a test device,
check cold start and the intro, D-pad navigation and page turns, Mii and Games
banners, opening an app, repeated Home returns, Settings, screen off/on and music
fade/resume. Compare screenshots, frame timing and memory against the base build
using the same device and workload. Check the clock across a minute, date and
timezone change. On Sabrina, also check Wi-Fi, the remote and Cast after a reboot.
Installation and device commands require the owner's approval.
