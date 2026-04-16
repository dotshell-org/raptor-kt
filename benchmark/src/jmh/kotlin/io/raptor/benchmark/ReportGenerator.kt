package io.raptor.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reads JMH JSON results and generates a Markdown benchmark report
 * with automatic interpretation of performance metrics.
 *
 * Usage: java -cp ... io.raptor.benchmark.ReportGenerator <results.json> <output.md>
 */
object ReportGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val inputPath = args.getOrNull(0) ?: "benchmark/build/reports/jmh/results.json"
        val outputPath = args.getOrNull(1) ?: "BENCHMARK_RESULTS.md"

        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            System.err.println("JMH results file not found: $inputPath")
            System.err.println("Run './gradlew :benchmark:jmh' first.")
            System.exit(1)
        }

        val results = JSONArray(inputFile.readText())
        val report = generateReport(results)

        File(outputPath).writeText(report)
        println("Report written to: $outputPath")
    }

    // --- Data extraction helpers ---

    private data class BenchmarkResult(
        val className: String,
        val method: String,
        val mode: String,
        val dataset: String,
        val score: Double,
        val error: Double,
        val unit: String,
        val gcAllocNorm: Double?,  // B/op
        val gcAllocRate: Double?,  // MB/sec
        val gcCount: Long?
    )

    private fun parseResults(jsonArray: JSONArray): List<BenchmarkResult> {
        val results = mutableListOf<BenchmarkResult>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val fullName = obj.getString("benchmark")
            val className = fullName.substringBeforeLast('.')
            val method = fullName.substringAfterLast('.')
            val mode = obj.optString("mode", "avgt")
            val params = obj.optJSONObject("params")
            val dataset = params?.optString("dataset") ?: "-"
            val primary = obj.getJSONObject("primaryMetric")
            val score = primary.getDouble("score")
            val error = primary.getDouble("scoreError")
            val unit = primary.getString("scoreUnit")

            val secondary = obj.optJSONObject("secondaryMetrics")
            // JMH prefixes GC profiler keys with middle dot (·)
            val gcAllocNorm = (secondary?.optJSONObject("\u00B7gc.alloc.rate.norm")
                ?: secondary?.optJSONObject("gc.alloc.rate.norm"))?.optDouble("score")
            val gcAllocRate = (secondary?.optJSONObject("\u00B7gc.alloc.rate")
                ?: secondary?.optJSONObject("gc.alloc.rate"))?.optDouble("score")
            val gcCount = (secondary?.optJSONObject("\u00B7gc.count")
                ?: secondary?.optJSONObject("gc.count"))?.optLong("score")

            results.add(BenchmarkResult(className, method, mode, dataset, score, error, unit, gcAllocNorm, gcAllocRate, gcCount))
        }
        return results
    }

    // --- Report generation ---

    private fun generateReport(jsonArray: JSONArray): String {
        val all = parseResults(jsonArray)
        val sb = StringBuilder()

        sb.appendLine("# RAPTOR-KT Benchmark Results")
        sb.appendLine()
        sb.appendLine("Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        sb.appendLine()

        renderSystemInfo(sb)

        // Filter by benchmark class
        val routing = all.filter { it.className.endsWith("RaptorBenchmark") && it.mode == "avgt" }
        val load = all.filter { it.className.endsWith("LoadBenchmark") }
        val memory = all.filter { it.className.endsWith("MemoryBenchmark") }

        if (routing.isNotEmpty()) renderRoutingSection(sb, routing)
        if (load.isNotEmpty()) renderLoadSection(sb, load)
        if (memory.isNotEmpty()) renderMemorySection(sb, memory)

        renderInterpretation(sb, routing, load, memory)
        renderJmhConfig(sb, jsonArray)

        return sb.toString()
    }

    // --- System info ---

    private fun renderSystemInfo(sb: StringBuilder) {
        sb.appendLine("## System Configuration")
        sb.appendLine()
        sb.appendLine("| Property | Value |")
        sb.appendLine("|:---------|:------|")
        sb.appendLine("| OS | ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")} |")
        sb.appendLine("| JVM | ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} |")
        sb.appendLine("| CPU Cores | ${Runtime.getRuntime().availableProcessors()} |")
        sb.appendLine("| Max Heap | ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB |")
        try {
            val gitHash = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
                .inputStream.bufferedReader().readText().trim()
            val gitBranch = Runtime.getRuntime().exec(arrayOf("git", "branch", "--show-current"))
                .inputStream.bufferedReader().readText().trim()
            sb.appendLine("| Git | `$gitBranch` @ `$gitHash` |")
        } catch (_: Exception) {
            sb.appendLine("| Git | (unavailable) |")
        }
        sb.appendLine()
    }

    // --- Routing section ---

    private fun renderRoutingSection(sb: StringBuilder, results: List<BenchmarkResult>) {
        sb.appendLine("## Routing Performance")
        sb.appendLine()
        sb.appendLine("1000 random seeded query pairs per dataset. JMH: 3 forks, 10 warmup, 20 measurement iterations.")
        sb.appendLine()

        val forward = results.filter { it.method == "forwardRouting" }.sortedBy { it.score }
        val arriveBy = results.filter { it.method == "arriveByRouting" }.sortedBy { it.score }

        if (forward.isNotEmpty()) {
            sb.appendLine("### Forward Routing")
            sb.appendLine()
            sb.appendLine("| Dataset | Avg (us/op) | Error | ms/op | GC alloc (B/op) | Verdict |")
            sb.appendLine("|:--------|------------:|------:|------:|----------------:|:--------|")
            for (r in forward) {
                val ms = r.score / 1000.0
                val gcStr = r.gcAllocNorm?.let { formatScore(it) } ?: "-"
                val verdict = rateForwardLatency(r.score)
                sb.appendLine("| ${r.dataset} | ${formatScore(r.score)} | +/- ${formatScore(r.error)} | ${formatMs(ms)} | $gcStr | $verdict |")
            }
            sb.appendLine()
        }

        if (arriveBy.isNotEmpty()) {
            sb.appendLine("### Arrive-By Routing")
            sb.appendLine()
            sb.appendLine("| Dataset | Avg (us/op) | Error | ms/op | GC alloc (B/op) | Verdict |")
            sb.appendLine("|:--------|------------:|------:|------:|----------------:|:--------|")
            for (r in arriveBy) {
                val ms = r.score / 1000.0
                val gcStr = r.gcAllocNorm?.let { formatScore(it) } ?: "-"
                val verdict = rateArriveByLatency(r.score)
                sb.appendLine("| ${r.dataset} | ${formatScore(r.score)} | +/- ${formatScore(r.error)} | ${formatMs(ms)} | $gcStr | $verdict |")
            }
            sb.appendLine()
        }

        // Ratio table
        if (forward.isNotEmpty() && arriveBy.isNotEmpty()) {
            sb.appendLine("### Arrive-By / Forward Ratio")
            sb.appendLine()
            sb.appendLine("| Dataset | Forward (us) | Arrive-By (us) | Ratio |")
            sb.appendLine("|:--------|-------------:|---------------:|------:|")
            for (fwd in forward) {
                val aby = arriveBy.find { it.dataset == fwd.dataset }
                if (aby != null) {
                    val ratio = aby.score / fwd.score
                    sb.appendLine("| ${fwd.dataset} | ${formatScore(fwd.score)} | ${formatScore(aby.score)} | ${String.format("%.1fx", ratio)} |")
                }
            }
            sb.appendLine()
        }
    }

    // --- Load section ---

    private fun renderLoadSection(sb: StringBuilder, results: List<BenchmarkResult>) {
        sb.appendLine("## Network Loading Time")
        sb.appendLine()
        sb.appendLine("Cold load: binary deserialization + Network construction. SingleShotTime, 5 forks.")
        sb.appendLine()
        sb.appendLine("| Dataset | Avg (ms) | Error | Verdict |")
        sb.appendLine("|:--------|--------:|------:|:--------|")
        for (r in results.sortedBy { it.score }) {
            val verdict = rateLoadTime(r.score)
            sb.appendLine("| ${r.dataset} | ${formatMs(r.score)} | +/- ${formatMs(r.error)} | $verdict |")
        }
        sb.appendLine()
    }

    // --- Memory section ---

    private fun renderMemorySection(sb: StringBuilder, results: List<BenchmarkResult>) {
        sb.appendLine("## Memory / GC Allocation")
        sb.appendLine()
        sb.appendLine("Per-query allocation measured via JMH GC profiler (SingleShotTime, 5 forks, 500 iterations).")
        sb.appendLine()
        sb.appendLine("| Dataset | Avg time (us) | GC alloc (B/op) | GC alloc rate | Verdict |")
        sb.appendLine("|:--------|-------------:|----------------:|--------------:|:--------|")
        for (r in results.sortedBy { it.score }) {
            val gcStr = r.gcAllocNorm?.let { formatScore(it) } ?: "-"
            val rateStr = r.gcAllocRate?.let { "${formatMs(it)} MB/s" } ?: "-"
            val verdict = r.gcAllocNorm?.let { rateGcAlloc(it) } ?: "-"
            sb.appendLine("| ${r.dataset} | ${formatScore(r.score)} | $gcStr | $rateStr | $verdict |")
        }
        sb.appendLine()
    }

    // --- Automatic interpretation ---

    private fun renderInterpretation(
        sb: StringBuilder,
        routing: List<BenchmarkResult>,
        load: List<BenchmarkResult>,
        memory: List<BenchmarkResult>
    ) {
        sb.appendLine("## Interpretation")
        sb.appendLine()

        val forward = routing.filter { it.method == "forwardRouting" }
        val arriveBy = routing.filter { it.method == "arriveByRouting" }

        // Forward routing analysis
        if (forward.isNotEmpty()) {
            val fastest = forward.minByOrNull { it.score }!!
            val slowest = forward.maxByOrNull { it.score }!!

            sb.appendLine("**Forward routing** — The primary hot path for real-time transit search.")
            sb.appendLine()
            if (slowest.score < 10_000) {
                sb.appendLine("- All datasets respond in **< 10 ms**, well within interactive latency budget.")
            } else {
                sb.appendLine("- Fastest: ${fastest.dataset} at ${formatMs(fastest.score / 1000.0)} ms. " +
                    "Slowest: ${slowest.dataset} at ${formatMs(slowest.score / 1000.0)} ms.")
            }
            sb.appendLine("- ${fastest.dataset} (${formatMs(fastest.score / 1000.0)} ms) is " +
                "${String.format("%.0fx", slowest.score / fastest.score)} faster than ${slowest.dataset} " +
                "(${formatMs(slowest.score / 1000.0)} ms), reflecting network size difference.")
            sb.appendLine()
        }

        // Arrive-by analysis
        if (forward.isNotEmpty() && arriveBy.isNotEmpty()) {
            val ratios = forward.mapNotNull { fwd ->
                val aby = arriveBy.find { it.dataset == fwd.dataset }
                aby?.let { it.score / fwd.score }
            }
            val avgRatio = ratios.average()

            sb.appendLine("**Arrive-by routing** — Binary search over departure times (default 120 min / 60s steps).")
            sb.appendLine()
            sb.appendLine("- Consistent **${String.format("%.1fx", avgRatio)} overhead** vs forward across all datasets.")
            if (avgRatio in 5.0..8.0) {
                sb.appendLine("- This matches the expected ~7 forward calls per arrive-by query (120 min / 60s binary search).")
            } else if (avgRatio > 8.0) {
                sb.appendLine("- Higher than expected ~7x ratio. May indicate some queries hitting worst-case search depth.")
            }
            sb.appendLine()
        }

        // Loading analysis
        if (load.isNotEmpty()) {
            val slowestLoad = load.maxByOrNull { it.score }!!
            sb.appendLine("**Network loading** — One-time cost at app startup.")
            sb.appendLine()
            if (slowestLoad.score < 200) {
                sb.appendLine("- All datasets load in **< 200 ms**. Fast enough for seamless app cold start.")
            } else {
                sb.appendLine("- Largest dataset (${slowestLoad.dataset}) loads in ${formatMs(slowestLoad.score)} ms. " +
                    "Consider async loading if > 500 ms.")
            }
            sb.appendLine()
        }

        // GC analysis
        if (memory.isNotEmpty()) {
            val maxAlloc = memory.mapNotNull { it.gcAllocNorm }.maxOrNull()
            sb.appendLine("**GC pressure** — Critical for Android UI smoothness (16ms frame budget).")
            sb.appendLine()
            if (maxAlloc != null && maxAlloc < 10_000) {
                sb.appendLine("- Per-query allocation **< ${formatScore(maxAlloc)} B** across all datasets. " +
                    "Negligible GC impact — no risk of jank.")
            } else if (maxAlloc != null) {
                sb.appendLine("- Peak allocation: ${formatScore(maxAlloc)} B/op. " +
                    if (maxAlloc < 50_000) "Acceptable for most use cases."
                    else "Consider reducing allocations in the hot path.")
            }
            sb.appendLine()
        }

        // Overall verdict
        sb.appendLine("### Overall Verdict")
        sb.appendLine()
        val fwdWorst = forward.maxByOrNull { it.score }
        val abyWorst = arriveBy.maxByOrNull { it.score }
        val maxAlloc = memory.mapNotNull { it.gcAllocNorm }.maxOrNull()

        val issues = mutableListOf<String>()
        if (fwdWorst != null && fwdWorst.score > 50_000) issues.add("Forward > 50ms on ${fwdWorst.dataset}")
        if (abyWorst != null && abyWorst.score > 200_000) issues.add("Arrive-by > 200ms on ${abyWorst.dataset}")
        if (maxAlloc != null && maxAlloc > 100_000) issues.add("GC alloc > 100KB/op")

        if (issues.isEmpty()) {
            sb.appendLine("All metrics are within excellent range for a production mobile transit app.")
        } else {
            sb.appendLine("Areas to investigate:")
            for (issue in issues) sb.appendLine("- $issue")
        }
        sb.appendLine()
    }

    // --- JMH config ---

    private fun renderJmhConfig(sb: StringBuilder, jsonArray: JSONArray) {
        sb.appendLine("## JMH Configuration")
        sb.appendLine()
        if (jsonArray.length() > 0) {
            val first = jsonArray.getJSONObject(0)
            sb.appendLine("| Setting | Value |")
            sb.appendLine("|:--------|:------|")
            sb.appendLine("| Forks | ${first.optInt("forks", -1)} |")
            sb.appendLine("| Warmup | ${first.optInt("warmupIterations", -1)} x ${first.optString("warmupTime", "?")} |")
            sb.appendLine("| Measurement | ${first.optInt("measurementIterations", -1)} x ${first.optString("measurementTime", "?")} |")
            sb.appendLine("| Mode | `${first.optString("mode", "N/A")}` |")
            sb.appendLine("| JVM | `${first.optJSONObject("jvmArgs")?.toString() ?: first.optString("jvm", "N/A")}` |")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("*Report generated by `io.raptor.benchmark.ReportGenerator`*")
        sb.appendLine()
    }

    // --- Verdict helpers ---

    private fun rateForwardLatency(us: Double): String = when {
        us < 500 -> "Excellent"
        us < 2_000 -> "Very good"
        us < 10_000 -> "Good"
        us < 50_000 -> "Acceptable"
        else -> "Slow"
    }

    private fun rateArriveByLatency(us: Double): String = when {
        us < 5_000 -> "Excellent"
        us < 20_000 -> "Very good"
        us < 50_000 -> "Good"
        us < 200_000 -> "Acceptable"
        else -> "Slow"
    }

    private fun rateLoadTime(ms: Double): String = when {
        ms < 20 -> "Instant"
        ms < 100 -> "Fast"
        ms < 500 -> "Good"
        ms < 2000 -> "Acceptable"
        else -> "Slow — consider async"
    }

    private fun rateGcAlloc(bytesPerOp: Double): String = when {
        bytesPerOp < 1_000 -> "Negligible"
        bytesPerOp < 10_000 -> "Low"
        bytesPerOp < 50_000 -> "Moderate"
        bytesPerOp < 200_000 -> "High"
        else -> "Excessive"
    }

    // --- Formatting ---

    private fun formatScore(value: Double): String = when {
        value >= 10_000 -> "%.0f".format(value)
        value >= 100 -> "%.1f".format(value)
        value >= 1 -> "%.2f".format(value)
        else -> "%.4f".format(value)
    }

    private fun formatMs(value: Double): String = when {
        value >= 100 -> "%.0f".format(value)
        value >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}
