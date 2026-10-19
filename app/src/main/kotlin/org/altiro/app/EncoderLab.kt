package org.altiro.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.EncoderExperiment
import org.altiro.core.RecognitionInput
import org.json.JSONArray
import org.json.JSONObject

/** Content-free, bounded partial results survive native worker and app restarts. */
class EncoderLab(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "encoder-lab.json"))
    private var report =
        try {
            if (file.baseFile.length() in 1..8_388_608)
                JSONObject(file.openRead().use { it.readBytes().decodeToString() })
            else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    private val mutableSummary = MutableStateFlow("")
    val summary = mutableSummary.asStateFlow()
    private var current: JSONObject? = null

    init {
        if (report.optString("status") == "RUNNING") {
            report.put("status", "INTERRUPTED")
            report.optJSONArray("cases")?.let { rows ->
                for (i in 0 until rows.length()) if (
                    rows.getJSONObject(i).optString("status") == "RUNNING"
                )
                    rows.getJSONObject(i).put("status", "INTERRUPTED")
            }
        }
        refresh()
    }

    fun reverseNextSuite(): Boolean =
        context
            .getSharedPreferences("encoder-lab", Context.MODE_PRIVATE)
            .getBoolean("reverse-next", false)

    @Synchronized
    fun begin(inputs: List<RecognitionInput>, cases: List<EncoderExperiment>, reverse: Boolean) {
        context
            .getSharedPreferences("encoder-lab", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("reverse-next", !reverse)
            .apply()
        report =
            JSONObject()
                .put("schema", 1)
                .put("status", "RUNNING")
                .put("runtime", "transcribepsy-364ec361")
                .put("app", BuildConfig.VERSION_NAME)
                .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put(
                    "measurement",
                    if (cases.any { it.variant >= 20 })
                        "full encoder: 1 first + 3 warm; synthetic kernels: 1 first + 5 warm; setup excluded"
                    else
                        "encoder only; first candidate call after CPU reference; then 3 resident warm calls",
                )
                .put("privacy", "No audio, features, transcript, editor identity or paths")
                .put("planned_cases", inputs.size * cases.size)
                .put("reverse_order", reverse)
                .put("planned_order", JSONArray(cases.map { it.id }))
                .put(
                    "models",
                    JSONArray(
                        inputs.map {
                            JSONObject().put("id", it.spec.id).put("sha256", it.spec.sha256)
                        }
                    ),
                )
                .put("cases", JSONArray())
        current = null
        save()
    }

    @Synchronized
    fun startCase(model: String, case: EncoderExperiment) {
        current =
            JSONObject()
                .put("model", model)
                .put("id", case.id)
                .put("backend_requested", case.backend.name)
                .put("threads", case.threads)
                .put("variant", case.variant)
                .put("profiled", case.profiled)
                .put("status", "RUNNING")
                .put("before", environment())
                .put("phase", "WORKER_SETUP")
                .put("samples", JSONArray())
                .put("kernels", JSONArray())
        report.getJSONArray("cases").put(current)
        save()
    }

    @Synchronized
    fun accept(raw: String) {
        require(raw.length <= 32768)
        val input = JSONObject(raw)
        require(input.optBoolean("lab"))
        val row = checkNotNull(current)
        when (val event = input.getString("event")) {
            "DEVICE" -> {
                val gpu = input.getString("gpu")
                val vulkan = input.getString("vulkan")
                require(gpu.length <= 96 && gpu.matches(Regex("[A-Za-z0-9 ._()+:-]*")))
                require(vulkan.length <= 32 && vulkan.matches(Regex("[0-9.]*")))
                for (key in
                    listOf(
                        "subgroup_size",
                        "shared_memory_bytes",
                        "max_workgroup_invocations",
                    )) require(input.getLong(key) in 0..1_073_741_824)
                row.put(
                    "device",
                    JSONObject()
                        .put("gpu", gpu)
                        .put("vulkan", vulkan)
                        .put("driver", input.getLong("driver"))
                        .put("subgroup_size", input.getLong("subgroup_size"))
                        .put("shared_memory_bytes", input.getLong("shared_memory_bytes"))
                        .put(
                            "max_workgroup_invocations",
                            input.getLong("max_workgroup_invocations"),
                        )
                        .put(
                            "timestamp_period_ns",
                            input.getDouble("timestamp_period_ns").also {
                                require(it.isFinite() && it >= 0)
                            },
                        )
                        .put(
                            "timestamp_bits_min",
                            input.getInt("timestamp_bits_min").also { require(it in -1..64) },
                        )
                        .put("timestamps_supported", input.getBoolean("timestamps_supported")),
                )
            }
            "PHASE" ->
                row.put(
                    "phase",
                    input.getString("phase").also {
                        require(it in listOf("REFERENCE", "CANDIDATE_LOAD"))
                    },
                )
            "CACHE_BEFORE",
            "CACHE_AFTER" -> {
                val files = input.getInt("files")
                val bytes = input.getLong("bytes")
                require(files in 0..1024 && bytes >= 0)
                row.put(event.lowercase(), JSONObject().put("files", files).put("bytes", bytes))
            }
            "BEGIN" ->
                row.put("phase", "ENCODER")
                    .put("repeat", input.getInt("repeat").also { require(it in 0..3) })
            "KERNEL" -> {
                val kernels = row.getJSONArray("kernels")
                require(row.getInt("variant") in 20..29 && kernels.length() < 32)
                require(input.getInt("sequence") == kernels.length())
                val kernel = JSONObject()
                for (key in
                    listOf(
                        "sequence",
                        "m",
                        "n",
                        "k",
                        "streams",
                        "activation_row_padding",
                        "resident_bytes",
                        "setup_ms",
                        "reference_cells",
                        "relative_rmse",
                        "max_abs_error",
                        "sampled_peak_pss_kib",
                    )) {
                    val value = input.getDouble(key)
                    require(value.isFinite() && value >= -1 && value < 1e12)
                    kernel.put(key, value)
                }
                require(kernel.getInt("m") in listOf(640, 1920, 2560))
                require(kernel.getInt("n") in listOf(32, 64, 65, 128, 129, 139, 256, 384))
                require(kernel.getInt("k") in listOf(640, 2560))
                require(kernel.getInt("streams") in listOf(1, 8))
                val variant = row.getInt("variant")
                require(
                    kernel.getInt("m") to kernel.getInt("k") in
                        listOf(2560 to 640, 640 to 2560, 1920 to 640, 640 to 640)
                )
                require(kernel.getInt("streams") == if (variant == 26) 8 else 1)
                require(kernel.getInt("reference_cells") == kernel.getInt("streams") * 64)
                require(kernel.getInt("activation_row_padding") == if (variant == 25) 32 else 0)
                val sourceType = if (row.getString("model") == "transcribepsy-q8") "q8_0" else "f16"
                require(input.getString("source_type") == sourceType)
                require(input.getString("storage_type") == if (variant == 24) "f16" else sourceType)
                require(input.getBoolean("f32_accumulation") == (variant >= 23))
                for (key in listOf("source_type", "storage_type")) kernel.put(
                    key,
                    input.getString(key).also { require(it in listOf("f16", "q8_0")) },
                )
                kernel.put(
                    "backend",
                    input.getString("backend").also {
                        require(it == "CPU" || it.matches(Regex("Vulkan[0-9]{1,2}")))
                        require((it == "CPU") == (row.getString("backend_requested") == "CPU"))
                    },
                )
                kernel.put("finite", input.getBoolean("finite"))
                kernel.put("f32_accumulation", input.getBoolean("f32_accumulation"))
                kernel.put("cpu_repacked", input.getBoolean("cpu_repacked"))
                require(
                    !input.getBoolean("cpu_repacked") || row.getString("backend_requested") == "CPU"
                )
                for (key in listOf("wall_ms", "process_cpu_ms", "readback_check_ms")) {
                    val values = input.getJSONArray(key)
                    require(values.length() == 6)
                    for (i in 0 until values.length()) require(
                        values.getDouble(i).let { it.isFinite() && it >= 0 && it < 1e9 }
                    )
                    kernel.put(key, values)
                }
                for (key in listOf("counts", "shaders")) kernel.put(
                    key,
                    measurementMap(input.getJSONObject(key)),
                )
                kernel.put("environment", environment())
                kernels.put(kernel)
                row.put("phase", "KERNEL ${kernels.length()}/32")
            }
            "SAMPLE" -> {
                val samples = row.getJSONArray("samples")
                require(samples.length() < 4)
                val sample = JSONObject()
                for (key in
                    listOf(
                        "repeat",
                        "wall_ms",
                        "process_cpu_ms",
                        "reference_setup_ms",
                        "candidate_load_ms",
                        "audio_ms",
                        "mel_frames",
                        "encoder_frames",
                        "width",
                        "relative_rmse",
                        "max_abs_error",
                        "sampled_peak_pss_kib",
                    )) {
                    if (input.has(key)) {
                        val value = input.getDouble(key)
                        require(value.isFinite() && value >= -1 && value < 1e300)
                        sample.put(key, value)
                    }
                }
                require(sample.getInt("repeat") == samples.length())
                sample.put("finite", input.getBoolean("finite"))
                sample.put(
                    "backend",
                    input.getString("backend").also {
                        require(it == "CPU" || it.matches(Regex("Vulkan[0-9]{1,2}")))
                    },
                )
                for (key in
                    listOf(
                        "host_us",
                        "counts",
                        "shaders",
                        "gpu_op_ns",
                        "gpu_shapes_ns",
                        "gpu_shapes_calls",
                    )) {
                    sample.put(key, measurementMap(input.getJSONObject(key)))
                }
                sample.put("environment", environment())
                samples.put(sample)
            }
            "FAILED",
            "CANCELLED" -> {
                row.put("status", event)
                if (input.optString("unsupported_op").isNotEmpty())
                    row.put(
                        "unsupported_op",
                        input.getString("unsupported_op").also {
                            require(it.matches(Regex("[A-Za-z0-9_:.-]{1,160}")))
                        },
                    )
                if (input.has("vk_result")) row.put("vk_result", input.getInt("vk_result"))
                row.put(
                    "failure",
                    input.getString("failure").also {
                        require(it.matches(Regex("[A-Z0-9_]{1,64}")))
                    },
                )
            }
            "FINISHED" -> {
                require(
                    if (row.getInt("variant") >= 20) row.getJSONArray("kernels").length() == 32
                    else row.getJSONArray("samples").length() == 4
                )
                row.put("status", event)
            }
            else -> error("Invalid lab event")
        }
        save()
    }

    @Synchronized
    fun endCase(status: String, failure: String? = null) {
        current?.let {
            if (it.optString("status") == "RUNNING") it.put("status", status)
            if (failure != null && !it.has("failure")) it.put("failure", failure)
            it.put("after", environment())
        }
        save()
    }

    private fun measurementMap(source: JSONObject): JSONObject {
        require(source.length() <= 100)
        val values = JSONObject()
        for (name in source.keys()) {
            require(name.matches(Regex("[A-Za-z0-9_:.-]{1,120}")))
            val value = source.getDouble(name)
            require(value.isFinite() && value >= 0 && value < 1e18)
            values.put(name, value)
        }
        return values
    }

    @Synchronized
    fun finish(status: String) {
        val rows = report.optJSONArray("cases")
        val failed =
            rows != null &&
                (0 until rows.length()).any {
                    rows.getJSONObject(it).optString("status") != "FINISHED"
                }
        report.put(
            "status",
            if (status == "FINISHED" && failed) "FINISHED_WITH_FAILURES" else status,
        )
        save()
    }

    @Synchronized
    fun clear() {
        current = null
        report = JSONObject()
        file.delete()
        refresh()
    }

    @Synchronized fun export(): String = report.toString(2)

    private fun environment(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val result =
            JSONObject()
                .put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                .put("thermal_status", power.currentThermalStatus)
                .put("power_save", power.isPowerSaveMode)
                .put(
                    "battery_deci_c",
                    battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1,
                )
                .put("battery_percent", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1)
                .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
        val frequencies = JSONArray()
        for (cpu in 0..7) {
            val khz =
                try {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
                        .readText()
                        .trim()
                        .toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            frequencies.put(khz ?: JSONObject.NULL)
        }
        return result.put("cpu_sample_khz", frequencies)
    }

    private fun save() {
        val bytes = report.toString().toByteArray()
        require(bytes.size <= 8_388_608)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        refresh()
    }

    private fun refresh() {
        val rows = report.optJSONArray("cases")
        mutableSummary.value =
            buildString {
                    if (rows == null) return@buildString
                    appendLine(
                        "${report.optString("status")} · ${rows.length()} / ${report.optInt("planned_cases")} cases"
                    )
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val kernels = row.optJSONArray("kernels")
                        if (row.optInt("variant") >= 20) {
                            append(
                                "${row.getString("model")} · ${row.getString("id")} · ${kernels?.length() ?: 0}/32 kernels · ${row.optString("status")}${if(row.has("failure")) " · ${row.getString("failure")}" else ""}"
                            )
                            if (
                                kernels != null &&
                                    (0 until kernels.length()).any {
                                        !kernels.getJSONObject(it).getBoolean("finite") ||
                                            kernels.getJSONObject(it).getDouble("relative_rmse") >
                                                0.01
                                    }
                            )
                                append(" · CHECK NUMERICS")
                            appendLine()
                            continue
                        }
                        val samples = row.getJSONArray("samples")
                        val warm =
                            (1 until samples.length()).map {
                                samples.getJSONObject(it).getDouble("wall_ms")
                            }
                        val median = EncoderExperiment.median(warm)
                        append("${row.getString("model")} · ${row.getString("id")} · ")
                        if (median != null)
                            append(
                                "${median.toLong()} ms warm (${warm.min().toLong()}–${warm.max().toLong()}) · "
                            )
                        append(row.optString("status"))
                        val error =
                            (0 until samples.length()).maxOfOrNull {
                                samples.getJSONObject(it).optDouble("relative_rmse", -1.0)
                            }
                        if (error != null && error > 0.01) append(" · CHECK NUMERICS")
                        if (row.optBoolean("profiled")) append(" · profiling overhead")
                        if (row.has("failure")) append(" · ${row.getString("failure")}")
                        if (
                            (0 until samples.length()).any {
                                !samples.getJSONObject(it).getBoolean("finite")
                            }
                        )
                            append(" · INVALID NUMERICS")
                        appendLine()
                    }
                }
                .trim()
    }
}
