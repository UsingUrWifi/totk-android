# Fold 5 native shadow draw-work A/B — November 5, 2026

Target: `UsingUrWifi/totk-android`, `touch-controls` after `c9430479`.

The previous 30 FPS priority profile lowered *shadow-map pixel dimensions*, which does not reduce the number of shadow-caster draws. Version 1.4.3 already contains the original guarded `TOTK_SHADOW_CASCADES` patch. The new **Graphics > Picture > Directional shadow passes** setting exposes 4 (original, unset), 3 and 2. **30 FPS priority** now selects 2; disabling it manually leaves the chosen settings in place, while selecting an ordinary preset or resetting Performance restores original 4 if priority had been active. Two passes may cause distant-shadow/transition artifacts. No game files are bundled or edited permanently.

**Distant scenery** controls the separately guarded `TOTK_FAR_CLAMP` render-camera option: original/unset, 1000m, 500m or 200m. It is **not automatically turned on by priority30**. Clamping may cause obvious world/horizon pop-in, even though collision, actors and streaming still operate normally. Use 500m only as a separate reversible test, and if 200m is tested expect substantial visual degradation. Options require the verified 1.4.3 game code; an unexpected game revision is rejected by the runtime rather than blindly patching bytes.

## A/B protocol

1. Keep current downloaded APK and export a warmed 60–90s route at 30 real FPS, 540p, shadows low, 60Hz preference, no frame generation, and normal CPU hints. Record *location*, camera direction, route and OS thermal status. This is the reference, with 4 cascades.
2. Install test APK, select **Graphics > Performance > 30 FPS priority > On**, restart, test the same route. The only *additional* reduction relative to the old priority is `TOTK_SHADOW_CASCADES=2` (check `[totk] 1.4.3 TOTK_SHADOW_CASCADES=2 applied` in `totk.log`). Toggle the native trace only for a short attribution capture; leave it off for performance comparisons.
3. If still below target, and the loss appears distant-draw-bound, change **Graphics > Picture > Distant scenery > 500 m** (this can show pop-in), restart and compare same route. Switch back to Game default after test if visual quality or performance is worse. Avoid changing multiple options together for causal attribution.
4. Require repeated demanding scenes, multiple minutes of play and image-quality/visibility checks. Two-second CSV FPS *alone* cannot prove a universal minimum or true screen scanout cadence. Compare game FPS and interval distributions including worst and long-frame counts; never label an average near 30 "locked 30".

Do not remove Vulkan fences, weaken AHB lifetime rules, change CPU/GPU governor or disable thermal protection. `TOTK_VK_REC_THREADS=2` is already the Android default and `TOTK_GPU_TIME=hardware` is already the native-30 default, so this patch does not add redundant switches.
