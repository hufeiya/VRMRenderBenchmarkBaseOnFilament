package com.neethu.filamentbasedbenchmark

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.rememberAvatarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Filament 渲染基准测试:全屏 [AvatarView] 渲染 assets 里的 VRM 模型,
 * 左上角 HUD 实时显示 FPS / PSS / 显存 / CPU,并每 5s 打一行 logcat(tag=BENCH)。
 *
 * 测量口径(与 dumpsys meminfo 一致):
 * - PSS  = Debug.MemoryInfo.totalPss(对应 dumpsys 的 TOTAL PSS)
 * - 显存 = summary.graphics(对应 dumpsys 的 Gfx Dev + EGL mtrack)
 * - CPU  = /proc/self/task/<tid>/stat 的 utime+stime 增量 / 墙钟时间,100% = 一个完整核心
 *
 * 冷启动:模型渲染就绪时 reportFullyDrawn(),配合 `adb shell am start -W` 与
 * logcat「Fully drawn」两个口径。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BenchmarkScreen() }
    }
}

private const val TAG = "BENCH"

@Composable
private fun BenchmarkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ① 渲染:与 SimpleDemoActivity 相同的三行接入,全屏铺底
    val controller = rememberAvatarController()
    val state by controller.state.collectAsState()

    LaunchedEffect(Unit) { controller.loadModel("vrms/AvatarSample_A.vrm") }

    // ② 指标采集:FPS 立即开始;模型 Ready 后静置 10s 再进入稳态采样(避开加载峰值)
    val collector = remember { MetricsCollector(scope) }
    DisposableEffect(collector) {
        collector.start()
        onDispose { collector.stop() }
    }

    LaunchedEffect(state) {
        if (state is AvatarState.Ready) {
            (context as? Activity)?.reportFullyDrawn()
            Log.i(TAG, "MODEL_READY")
            collector.beginSteadyStateSampling()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AvatarView(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            config = AvatarConfig(
                renderSettings = com.neethu.corelib.AvatarRenderSettings(
                    ambientOcclusion = com.neethu.corelib.AmbientOcclusionQuality.OFF,
                    antiAliasing = com.neethu.corelib.AntiAliasingMode.NONE,
                ),
            ),
        )

        when (val s = state) {
            is AvatarState.Loading -> StatusText("加载模型中…", Modifier.align(Alignment.TopCenter))
            is AvatarState.Error -> StatusText("加载失败:${s.message}", Modifier.align(Alignment.TopCenter))
            else -> {}
        }

        MetricsHud(collector, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun MetricsHud(collector: MetricsCollector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 24.dp, start = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = collector.hudText(),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 指标采集器:Choreographer 数帧得 FPS;/proc 采样全线程 CPU;Debug.MemoryInfo
 * 取 PSS 与显存。稳态采样每 [SAMPLE_INTERVAL_MS] 一次,结果进 snapshot state
 * (驱动 HUD 重组)并打 logcat。
 */
private class MetricsCollector(private val scope: CoroutineScope) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private var frameCount = 0
    private var windowStartNs = 0L

    var fps by mutableFloatStateOf(0f); private set
    var pssTotalMb by mutableIntStateOf(0); private set
    var gfxMb by mutableIntStateOf(0); private set
    var cpuPercent by mutableFloatStateOf(0f); private set
    var steadySamples by mutableIntStateOf(0); private set

    fun hudText(): String = buildString {
        append("FPS %.1f".format(fps))
        if (steadySamples > 0) {
            append("\nTOTAL PSS ${pssTotalMb} MB\nGraphics ${gfxMb} MB")
            append("\nCPU %.1f%%".format(cpuPercent))
        }
    }

    fun start() {
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        choreographer.removeFrameCallback(this)
    }

    /** 模型 Ready 后调用:静置 [SETTLE_MS] 避开加载峰值,然后进入稳态采样循环。 */
    fun beginSteadyStateSampling() {
        scope.launch {
            delay(SETTLE_MS)
            var lastTicks = withContext(Dispatchers.Default) { readCpuTicks() }
            var lastTs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val ts = SystemClock.elapsedRealtime()
                val ticks = withContext(Dispatchers.Default) { readCpuTicks() }

                val wallSec = (ts - lastTs) / 1000.0
                // Android USER_HZ 恒为 100:100 tick/s = 1 个完整核心跑满
                cpuPercent = ((ticks - lastTicks) / 100.0 / wallSec * 100).toFloat()

                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                pssTotalMb = mi.totalPss / 1024
                gfxMb = (mi.getMemoryStats()["summary.graphics"]?.toInt() ?: 0) / 1024
                steadySamples++

                Log.i(
                    TAG,
                    "STEADY sample=$steadySamples fps=${"%.1f".format(fps)} " +
                        "cpuOneCore=${"%.1f".format(cpuPercent)}% " +
                        "pssTotal=${pssTotalMb}MB gfx=${gfxMb}MB",
                )
                lastTicks = ticks
                lastTs = ts
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (windowStartNs == 0L) windowStartNs = frameTimeNanos
        frameCount++
        val elapsedSec = (frameTimeNanos - windowStartNs) / 1_000_000_000.0
        if (elapsedSec >= 1.0) {
            fps = (frameCount / elapsedSec).toFloat()
            frameCount = 0
            windowStartNs = frameTimeNanos
        }
        choreographer.postFrameCallback(this)
    }

    /** 全部线程的 utime+stime 总 tick 数。 */
    private fun readCpuTicks(): Long {
        var total = 0L
        val tasks = File("/proc/self/task").listFiles() ?: return 0
        for (tid in tasks) {
            runCatching {
                val stat = File(tid, "stat").readText()
                // 第 2 字段 comm 可能含空格,从最后一个 ')' 之后切分;
                // 去掉 state 后 utime 是第 11、stime 是第 12 个(0-based)
                val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                total += fields[11].toLong() + fields[12].toLong()
            }
        }
        return total
    }

    companion object {
        private const val SETTLE_MS = 10_000L
        private const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
