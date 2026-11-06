# Fold 5 display/cadence test profile — 2026-11-05

Target branch: `touch-controls`, after the existing ZL toggle / M1 combo and read-only diagnostics update (`306855b`).

## What the user's diagnostic CSV establishes

- An extended idle period reported 24 Hz and roughly 12 completed Vulkan handoffs per second.
- On touching the display, the reported mode returned to 120 Hz, with partial FPS recovery.
- These are correlated observations, not a measurement of actual physical vsync or GPU frequency.
- Other active gameplay intervals also fall to 20–25 FPS; a refresh request alone may not fix these.

## Change set

1. `Graphics > Screen > Display refresh` defaults to `Prefer 60 Hz` in **this experimental test branch**; `System auto` restores prior policy. It writes `TOTK_FRAME_RATE_HINT=60` to the existing validated `settings.env` path.
2. `runtime/android_perf.c` requests 60Hz consistently through the native-window and SurfaceControl paths, regardless of 30fps simulation/framegen setting. It uses `ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT` (game-compatible) instead of `FIXED_SOURCE` (video-specific). The renderer already applies these hints when the native window/surface is created and when content-rate changes.
3. Java `TotkActivity` additionally sets `WindowManager.LayoutParams.preferredRefreshRate` to 60.0 for the activity window. This is a fallback/extra vote for AHB presentation when the system can honor it, not a forced display mode or a governor change. It does not use `preferredDisplayModeId`.
4. `Graphics > Performance > CPU performance hints` offers `Current` (default) and `Full (test)`. The latter writes `TOTK_ADPF=full`, opting into the existing game/render/presenter/worker session if supported by Android. This is a **separate A/B experiment**, not a verified performance cure.
5. `Controls & audio > Pipeline cache logging` offers `Off` (default) or `On`. Enabling writes `TOTK_PCACHE_STATS=1`, displaying cache extraction/snapshot timings in the game's `totk.log`. Existing persistent pipeline caches, seed validation, asynchronous shader pipelines, and shader prewarm are unchanged.

## A/B test protocol

**Baseline A:** Before installing test build, capture existing PAD monitor for 15–20 seconds idle (the same scene), then 15–20 seconds interaction. Save the CSV. Use 30fps real game / no framegen at the same location and camera for both runs.

**Preference B:** Install experimental test build, leave `Display refresh = Prefer 60 Hz`, `CPU performance hints = Current`, `Pipeline cache logging = Off`, restart game, wait for warmup, repeat exactly. If the idle mode stays >=60 Hz and game FPS no longer collapses to ~12, the floor was likely dominated by display-mode selection. If display mode still falls to 24 or game FPS remains near 12, system choice/guest cadence needs further work; do not assume the hint was obeyed.

**Optional CPU profile C:** Change only `CPU performance hints` to `Full (test)`, restart and compare the same scene. Look for 1% lows, CPU use and thermal changes over a consistent period. Restore to `Current` if worse or warmer.

**Optional pipeline logs D:** Enable `Pipeline cache logging`, restart, reproduce an *active-play* shader hitch, and share only the relevant `[pcache-stats]` and `long frame` lines from `totk.log`. The game already emits per-long-frame attribution for graphics-pipeline create duration, compute pipelines, seed file reads, and GPU waits. These distinguish real compilation stalls from a 24Hz frame pacing collapse. Avoid high-volume `TOTK_PRESENT_CADENCE_TRACE=1` during normal comparisons; the project's own docs say it can perturb frame rate on some hardware.

## Safety/limitations

- This patch does **not** boost CPU/GPU clocks, toggle thermal limits, inject fake touches, replace Turnip, reset shader caches or change game files.
- It does **not** promise 30/60fps or lock a physical display mode: the Android compositor can ignore preferences; two display surfaces compete for refresh votes.
- The game may need to reload/rebuild local caches after a driver update; do not overwrite or delete them for this test.
- The `ZL` latch, `M1` combos, haptics, and touch panel layouts remain untouched.
- Full pipeline compiler surgery should follow evidence that `long frame` events actually correlate with shader builds/seed IO; increasing compilation concurrency blindly may cause GPU driver contention, heat and worse 1% lows.
- Test APK build success and on-device validation still required. Build uses the project's isolated `TotK Touch v2` package.
