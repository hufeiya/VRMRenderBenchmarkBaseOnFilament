# FilamentBasedBenchmark

Native Android VRM rendering benchmark with [Google Filament](https://github.com/google/filament),
no WebView, no Unity runtime — built to return data to
[airi's DevLog 2026.03.23](https://airi.moeru.ai/docs/en/blog/DevLog-2026.03.23/)
engine exploration (see [ISSUE_DRAFT.md](ISSUE_DRAFT.md)).

Rendering stack: [AIAvatar-SDK `corelib`](https://github.com/hufeiya/AIAvatar-SDK)
(Filament 1.68.3 + VRM 0.x/1.0 loading + MToon shading + spring bones) — pulled as a
single Maven dependency, `MainActivity.kt` is the whole app (~200 lines incl. the
metrics HUD).

## Reproduce

```bash
# 1. build
./gradlew :app:assembleDebug

# 2. install (MIUI may ask for confirmation on-device)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. cold start + steady-state metrics + dumpsys collection + screenshot
./tools/benchmark.sh run
```

Results land in `benchmark-results/`: cold start (`am start -W` + logcat
`Fully drawn`), in-app samples (log tag `BENCH`: FPS / PSS / graphics / CPU every 5 s),
`dumpsys meminfo` / `gfxinfo`, `top` snapshot, screenshot.

## Metrics口径

| Metric | Source |
| --- | --- |
| FPS | `Choreographer` frame counting (1 s window), cross-checked with `dumpsys gfxinfo` |
| Total RAM | `Debug.MemoryInfo.totalPss` == `dumpsys meminfo` TOTAL PSS, sampled ≥10 s after model ready |
| Graphics memory | `summary.graphics` (Gfx Dev + EGL mtrack) |
| CPU | Σ `utime+stime` over `/proc/<pid>/task/*/stat` ÷ wall time; 100 % = one full core |
| Cold start | `am start -W` TotalTime (first frame) + `reportFullyDrawn()` (model rendered) |

Conditions: 60 Hz display, screen on / USB powered, static pose with spring-bone
physics, built-in IBL, model `assets/vrms/AvatarSample_A.vrm` (VRoid's official
sample avatar — the same model airi's DevLog benchmark uses) rendered through the
MToon path.

## Size breakdown (measured 2026-10-09)

| Item | Size |
| --- | --- |
| Filament AARs (download, all ABIs) | 23.0 MB — `filament-android` 4.3 + `gltfio-android` 6.7 + `filamat-android` 11.1 + `filament-utils-android` 1.0 |
| corelib AAR (download, all ABIs) | 2.1 MB |
| arm64-v8a native libs installed per device | 13.6 MB (`libfilamat-jni` 7.79 + `libgltfio-jni` 2.66 + `libfilament-jni` 2.50 + `libfilament-utils-jni` 0.43 + `libcorelib` 0.24) |
| Empty Compose app APK (universal, debug) | 28.4 MB |
| Benchmark APK (engine + 15 MB VRM + 2 MB IBL) | 94.7 MB |
