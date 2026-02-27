package io.raptor.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reads JMH JSON results and generates a Markdown benchmark report.
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

    private fun generateReport(results: JSONArray): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("# RAPTOR-KT Benchmark Results")
        sb.appendLine()
        sb.appendLine("Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        sb.appendLine()

        // System info
        sb.appendLine("## System Configuration")
        sb.appendLine()
        sb.appendLine("| Property | Value |")
        sb.appendLine("|:---------|:------|")
        sb.appendLine("| OS | ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")} |")
        sb.appendLine("| JVM | ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} |")
        sb.appendLine("| CPU Cores | ${Runtime.getRuntime().availableProcessors()} |")
        sb.appendLine("| Max Heap | ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB |")

        // Git info
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

        // Group results by benchmark class
        val benchmarksByClass = mutableMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val fullName = result.getString("benchmark")
            val className = fullName.substringBeforeLast('.')
            benchmarksByClass.getOrPut(className) { mutableListOf() }.add(result)
        }

        // Routing benchmarks
        val routingResults = benchmarksByClass.entries.find { it.key.endsWith("RaptorBenchmark") }
        if (routingResults != null) {
            sb.appendLine("## Routing Performance")
            sb.appendLine()
            sb.appendLine("1000 random seeded query pairs per dataset. JMH: 3 forks, 10 warmup, 20 measurement iterations.")
            sb.appendLine()
            renderBenchmarkTable(sb, routingResults.value)
        }

        // Load benchmarks
        val loadResults = benchmarksByClass.entries.find { it.key.endsWith("LoadBenchmark") }
        if (loadResults != null) {
            sb.appendLine("## Network Loading Time")
            sb.appendLine()
            sb.appendLine("Cold load: binary deserialization + Network construction. SingleShotTime, 5 forks.")
            sb.appendLine()
            renderBenchmarkTable(sb, loadResults.value)
        }

        // Memory benchmarks
        val memResults = benchmarksByClass.entries.find { it.key.endsWith("MemoryBenchmark") }
        if (memResults != null) {
            sb.appendLine("## Memory / GC Allocation")
            sb.appendLine()
            renderBenchmarkTable(sb, memResults.value)
            renderGcMetrics(sb, memResults.value)
        }

        // JMH config summary
        sb.appendLine("## JMH Configuration")
        sb.appendLine()
        if (results.length() > 0) {
            val first = results.getJSONObject(0)
            sb.appendLine("- Fork: ${first.optInt("forks", -1)}")
            sb.appendLine("- Warmup: ${first.optInt("warmupIterations", -1)} iterations")
            sb.appendLine("- Measurement: ${first.optInt("measurementIterations", -1)} iterations")
            sb.appendLine("- Mode: ${first.optString("mode", "N/A")}")
        }
        sb.appendLine()

        return sb.toString()
    }

    private fun renderBenchmarkTable(sb: StringBuilder, results: List<JSONObject>) {
        sb.appendLine("| Benchmark | Dataset | Score | Error | Unit |")
        sb.appendLine("|:----------|:--------|------:|------:|:-----|")

        for (result in results) {
            val fullName = result.getString("benchmark")
            val method = fullName.substringAfterLast('.')
            val params = result.optJSONObject("params")
            val dataset = params?.optString("dataset") ?: "-"
            val primary = result.getJSONObject("primaryMetric")
            val score = primary.getDouble("score")
            val error = primary.getDouble("scoreError")
            val unit = primary.getString("scoreUnit")

            sb.appendLine("| $method | $dataset | ${formatScore(score)} | ± ${formatScore(error)} | $unit |")
        }
        sb.appendLine()
    }

    private fun renderGcMetrics(sb: StringBuilder, results: List<JSONObject>) {
        val gcRows = mutableListOf<Triple<String, String, String>>() // method, dataset, alloc

        for (result in results) {
            val fullName = result.getString("benchmark")
            val method = fullName.substringAfterLast('.')
            val params = result.optJSONObject("params")
            val dataset = params?.optString("dataset") ?: "-"
            val secondary = result.optJSONObject("secondaryMetrics") ?: continue
            val allocNorm = secondary.optJSONObject("gc.alloc.rate.norm")
            if (allocNorm != null) {
                gcRows.add(Triple(method, dataset, "${formatScore(allocNorm.getDouble("score"))} B/op"))
            }
        }

        if (gcRows.isNotEmpty()) {
            sb.appendLine("### GC Allocation per Operation")
            sb.appendLine()
            sb.appendLine("| Benchmark | Dataset | Alloc/op |")
            sb.appendLine("|:----------|:--------|:---------|")
            for ((method, dataset, alloc) in gcRows) {
                sb.appendLine("| $method | $dataset | $alloc |")
            }
            sb.appendLine()
        }
    }

    private fun formatScore(value: Double): String {
        return if (value >= 1000) {
            "%.0f".format(value)
        } else if (value >= 1) {
            "%.2f".format(value)
        } else {
            "%.4f".format(value)
        }
    }
}
