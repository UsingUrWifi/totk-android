# Fold 5 visual-integrity baseline (November 5, 2026)

Test phone: Galaxy Z Fold 5 / Snapdragon 8 Gen 2 / Adreno 740; experimental native TOTK Android test APK. Reports showed stable Android-reported 60 Hz but real game FPS dropping to 19–21 in a demanding wooded route. This does not prove display scanout cadence or identify a single Vulkan fault.

## Observed/reported defects and plausible causes

- **Tree shadows and lighting shimmer/flicker:** the earlier experimental Priority30 profile forced two cascades instead of four and 256-pixel shadow maps instead of native shadow quality. Cascade boundaries and reduced shadow precision can make shadows pop/shimmer; this is a hypothesis until same-route on-device A/B proves it. The video includes animated vegetation and moving cameras, so not every brightness change is a renderer defect.
- **Foliage edge shimmer:** forcing the game's FXAA off increases high-frequency aliasing at 540p. Original FXAA is restored, but spatial FXAA is not temporal AA and cannot eliminate all animated foliage shimmer.
- **Distant scenery popping/clipping:** the opt-in 500 m camera far-plane cap removes distant geometry on purpose. Original camera distance is restored and no longer silently persists in Priority30. **Physics objects physically passing through other geometry** are a different defect, not fixed by this camera setting.
- **Other flashing / incorrect sun lighting / missing faces:** if reproducible with original visuals, treat as an open Vulkan/Turnip shader, depth, precision, or memory-layout issue. Do not assert a shader or driver fix without isolated evidence.

## Corrective change

When the **30 FPS priority** switch is On (including loading the stored On value after installing the new APK), the launcher now uses:

| Setting | Corrected profile | Earlier experimental profile |
|---|---|---|
| Real frame rate | 30 fps, framegen Off | Same |
| Android refresh request | Prefer 60 Hz | Same |
| Scene resolution | 960x540 (still lower than native) | Same |
| Shadows | High, game-native map size | Low, forced 256px |
| Directional shadow passes | Original (4) | Forced 2 |
| Anti-aliasing | Game (FXAA) | Off |
| Distant scenery | Game default | Possible persistent manual 500 m cap |
| Other | No extra upscaler, balanced latency; normal CPU hints | Same |

`TOTK_SHADOW_CASCADES`, `TOTK_SHADOW_RES`, `TOTK_FXAA=0`, and `TOTK_FAR_CLAMP` are omitted from the generated settings.env in corrected Priority30. Other native options and safeguards are unchanged. Experimental shadow/distance controls are still manually selectable but exit the protected Priority30 label when changed.

## Repeatable A/B

1. Confirm that the test APK was built from the intended commit. Start a fresh game process so the `settings.env` values are read again. Select 30 FPS priority On and verify the four settings under Graphics > Picture.
2. Use the **same save**, camera angle, weather/time (as close as feasible), and route through the previously affected trees and rocks. Record 30–60 seconds stationary while rotating camera slowly, then a 1–2 minute traversal. Hold scene resolution and driver fixed across the comparison.
3. Watch for moving tree shadows, incorrect bright/dark flashes, missing distant scenery and texture/geometry clipping. Do not infer cause from a single compressed frame. Log the exact reproducible time/position in the recording.
4. Export the PAD performance report. If shadow shimmer improves but FPS worsens, we have identified a quality/performance trade-off, **not** a 30 FPS solution. Preserve correct visuals and optimize renderer CPU/GPU sync separately.
5. If defects persist unchanged, compare **identical APK + game data** with a known compatible different Turnip driver as a separate A/B only after backing up your current known-good driver and cache. Do not purge game files, change kernel clocks, disable synchronization/fences, or weaken thermal protection.

Acceptance: visual quality must at least match game-native shadows and horizon, without recurring gross illumination flashes. Performance acceptance still requires repeated **30 real FPS / 33.3 ms** frame intervals in demanding scenes; a median of 30 alone is not sufficient. 40 FPS is a later milestone.
