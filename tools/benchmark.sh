#!/usr/bin/env bash
# 一键渲染基准测试:安装(可选) → 冷启动 → 稳态采样 → 数据采集 → 截图
# 产物落在 benchmark-results/,数据口径见 MainActivity.kt 头注释。
#
# 用法:
#   ./tools/benchmark.sh install   # 先安装(MIUI 弹窗需在手机上点允许)
#   ./tools/benchmark.sh run       # 冷启动 + 采集(设备保持亮屏)
#   ./tools/benchmark.sh shot      # 只截屏
set -e
cd "$(dirname "$0")/.."

PKG=com.neethu.filamentbasedbenchmark
ACT=$PKG/.MainActivity
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=benchmark-results
mkdir -p "$OUT"

case "${1:-run}" in
install)
    adb install -r "$APK" || {
        echo "adb install 被拒(MIUI USER_RESTRICTED),回退 pm install:"
        adb push "$APK" /data/local/tmp/bench.apk
        echo ">>> 请盯着手机,弹窗出现时点「允许」"
        adb shell pm install -r /data/local/tmp/bench.apk
    }
    ;;

shot)
    adb exec-out screencap -p > "$OUT/shot_$(date +%H%M%S).png"
    ;;

run)
    adb shell svc power stayon usb   # USB 供电时常亮,避免测量中途熄屏
    adb shell am force-stop "$PKG"
    sleep 2

    # ── 冷启动:am start -W(TotalTime=首帧)+ logcat Fully drawn(模型渲染就绪)
    echo "── 冷启动 ──"
    adb logcat -c
    adb shell am start -W -n "$ACT" | tee "$OUT/coldstart.txt"
    sleep 40   # 模型 Ready + 10s 静置 + ≥5 个稳态采样(每个 5s)
    adb logcat -d | grep -E "Fully drawn|BENCH" > "$OUT/bench.log"
    cat "$OUT/bench.log"

    # ── 内存/显存(dumpsys 口径,与 HUD 同源交叉验证)
    adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt"
    grep -E "TOTAL PSS|Gfx Dev|EGL mtrack|TOTAL:" "$OUT/meminfo.txt" || true

    # ── 帧统计(jank 比例)
    adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
    grep -E "Total frames|Janky frames" "$OUT/gfxinfo.txt" || true

    # ── 系统侧 CPU(top 口径,与应用内 /proc 口径互参)
    adb shell "top -n 1 -b -o %CPU,CMDL" 2>/dev/null | grep -i filament > "$OUT/top.txt" ||
        adb shell top -n 1 -b | grep -i filament | tee "$OUT/top.txt"
    cat "$OUT/top.txt"

    adb exec-out screencap -p > "$OUT/screenshot_front.png"
    echo "── 完成,产物在 $OUT/ ──"
    ;;
esac
