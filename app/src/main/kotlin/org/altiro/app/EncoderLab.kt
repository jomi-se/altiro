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
    internal val relay = EncoderLabRelay()
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
                .put("live_relay_enabled", relay.connected)
                .put("status", "RUNNING")
                .put("runtime", "transcribepsy-364ec361")
                .put("app", BuildConfig.VERSION_NAME)
                .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put(
                    "measurement",
                    if (cases.any { it.variant in 60..63 || it.variant == 70 })
                        "packed GEMM: 1 first + 5 warm; complete calls include activation packing; capability probes are not timings of the encoder"
                    else if (cases.any { EncoderExperiment.isKernel(it.variant) })
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
                .put("packed", JSONArray())
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
            "BEGIN" -> {
                require(row.getInt("variant") !in 60..70)
                row.put("phase", "ENCODER")
                    .put("repeat", input.getInt("repeat").also { require(it in 0..3) })
            }
            "PACKED" -> acceptPacked(input, row)
            "CAPABILITY" -> {
                require(row.getInt("variant") == 70 && !row.has("capabilities"))
                val source = input.getJSONObject("capabilities")
                row.put(
                    "capabilities",
                    JSONObject()
                        .put("opencl", capability(source.getJSONObject("opencl"), true))
                        .put("vulkan", capability(source.getJSONObject("vulkan"), false)),
                )
                row.put("phase", "CAPABILITIES")
            }
            "KERNEL" -> {
                val kernels = row.getJSONArray("kernels")
                val variant = row.getInt("variant")
                val focused = variant in 40..46
                val expected = EncoderExperiment.kernelCount(variant)
                require(EncoderExperiment.isKernel(variant) && kernels.length() < expected)
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
                if (focused) {
                    val frames = input.getInt("encoder_frames").also { require(it in 1..4096) }
                    val sequence = input.getInt("sequence")
                    val shape = listOf(kernel.getInt("m"), kernel.getInt("n"), kernel.getInt("k"))
                    require(
                        shape ==
                            listOf(
                                listOf(2560, frames, 640),
                                listOf(640, frames, 2560),
                                listOf(640, frames, 1280),
                                listOf(640, 2 * frames - 1, 640),
                                listOf(64, 63, 64),
                                listOf(65, 65, 96),
                                listOf(640, 129, 2560),
                                listOf(640, 257, 2560),
                            )[sequence]
                    )
                    require(input.getBoolean("focused"))
                    require(
                        input.getString("shape_family") ==
                            if (sequence < 4) "recording" else "boundary"
                    )
                    require(input.getString("reference_mode") == "double_dot_f32_bias_silu")
                    require(input.getBoolean("bias_requested"))
                    require(input.getString("activation_requested") == "silu")
                    require(
                        input.getInt("fusion_requested") ==
                            if (variant == 45) 1 else if (variant == 46) 2 else 0
                    )
                    val split =
                        if (shape[0] == 640 && shape[2] == 2560) {
                            if (variant == 43) 2 else if (variant == 44) 4 else 1
                        } else 1
                    require(input.getInt("split_requested") == split)
                    val cells = kernel.getInt("m") * kernel.getInt("n")
                    require(input.getBoolean("full_reference") == (cells <= 8192))
                    require(kernel.getInt("reference_cells") == if (cells <= 8192) cells else 64)
                    for (key in
                        listOf(
                            "focused",
                            "encoder_frames",
                            "shape_family",
                            "reference_mode",
                            "full_reference",
                            "bias_requested",
                            "activation_requested",
                            "fusion_requested",
                            "split_requested",
                        )) kernel.put(key, input.get(key))
                    val compared = input.getBoolean("unfused_compared")
                    require(compared == (variant in 45..46))
                    kernel.put("unfused_compared", compared)
                    for (key in listOf("unfused_relative_rmse", "unfused_max_abs_error")) {
                        if (!compared) {
                            require(input.has(key) && input.isNull(key))
                            kernel.put(key, JSONObject.NULL)
                        } else {
                            val value = input.getDouble(key)
                            require(value.isFinite() && value >= -1 && value < 1e12)
                            kernel.put(key, value)
                        }
                    }
                    for (key in listOf("boundary_gpu_ns", "enqueue_us", "fence_wait_us")) {
                        val values = input.getJSONArray(key)
                        require(values.length() == 6)
                        for (i in 0 until values.length()) {
                            if (key == "boundary_gpu_ns" && values.isNull(i)) continue
                            val value = values.getDouble(i)
                            require(value.isFinite() && value >= 0 && value < 1e18)
                        }
                        kernel.put(key, values)
                    }
                    kernel.put("us", measurementMap(input.getJSONObject("us")))
                } else {
                    require(
                        kernel.getInt("m") to kernel.getInt("k") in
                            listOf(2560 to 640, 640 to 2560, 1920 to 640, 640 to 640)
                    )
                    require(kernel.getInt("n") in listOf(32, 64, 65, 128, 129, 139, 256, 384))
                    require(kernel.getInt("reference_cells") == kernel.getInt("streams") * 64)
                }
                require(kernel.getInt("streams") == if (variant == 26) 8 else 1)
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
                row.put("phase", "KERNEL ${kernels.length()}/$expected")
            }
            "SAMPLE" -> {
                require(row.getInt("variant") !in 60..70)
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
                    if (row.getInt("variant") == 70)
                        row.optJSONObject("capabilities")?.let {
                            it.optJSONObject("opencl") != null && it.optJSONObject("vulkan") != null
                        } == true
                    else if (row.getInt("variant") in 60..63)
                        row.getJSONArray("packed").length() == 4
                    else if (EncoderExperiment.isKernel(row.getInt("variant")))
                        row.getJSONArray("kernels").length() ==
                            EncoderExperiment.kernelCount(row.getInt("variant"))
                    else row.getJSONArray("samples").length() == 4
                )
                row.put("status", event)
            }
            else -> error("Invalid lab event")
        }
        save()
    }

    private fun acceptPacked(input: JSONObject, row: JSONObject) {
        val variant = row.getInt("variant")
        require(variant in 60..63 && row.getString("backend_requested") == "VULKAN")
        val records = row.getJSONArray("packed")
        val sequence = input.getInt("sequence")
        require(sequence == records.length() && sequence in 0..3)
        val frames = input.getInt("encoder_frames").also { require(it in 1..1000) }
        if (records.length() > 0)
            require(frames == records.getJSONObject(0).getInt("encoder_frames"))
        val width = if (variant == 61) 145 else if (variant == 62) 201 else frames
        val shape =
            listOf(
                listOf(2560, width, 640),
                listOf(640, width, 2560),
                listOf(65, 65, 96),
                listOf(63, 63, 64),
            )[sequence]
        val streams = if (variant == 63) 2 else 1
        val full = shape[0] * shape[1] <= 8192
        val record = JSONObject()
        val integers =
            mapOf(
                "sequence" to sequence,
                "encoder_frames" to frames,
                "m" to shape[0],
                "n" to shape[1],
                "k" to shape[2],
                "streams" to streams,
                "complete_calls_per_repeat" to streams,
                "reference_cells" to (if (full) shape[0] * shape[1] else 64) * streams,
                "candidate_gpu_dispatches_per_repeat" to streams * 2,
                "candidate_shared_memory_bytes" to 0,
                "physical_device_count" to 1,
            )
        for ((key, expected) in integers) {
            require(input.get(key) is Number && input.getDouble(key) == expected.toDouble())
            record.put(key, expected)
        }
        val strings =
            mapOf(
                "source_type" to
                    if (row.getString("model") == "transcribepsy-q8") "q8_0" else "f16",
                "storage_type" to "f16",
                "backend" to "Vulkan",
                "control_backend" to "Vulkan",
                "implementation" to "altiro_packed_register4_v1",
                "weight_layout" to "m4_k_mlane",
                "activation_layout" to "n4_k_nlane_f32",
                "activation_input_rounding" to "f16_then_f32",
                "reference_mode" to "double_dot_half_rounded_inputs",
                "execution_order" to "alternating_candidate_first_even",
            )
        for ((key, expected) in strings) {
            require(input.get(key) == expected)
            record.put(key, expected)
        }
        for ((key, expected) in
            mapOf(
                "f32_accumulation" to true,
                "wall_includes_activation_pack" to true,
                "weight_pack_checked" to true,
                "activation_pack_checked" to true,
                "full_reference" to full,
                "same_device_verified" to true,
            )) {
            require(input.get(key) == expected)
            record.put(key, expected)
        }
        require(input.get("finite") is Boolean)
        record.put("finite", input.getBoolean("finite"))
        for ((key, expected) in
            mapOf(
                "workgroup" to listOf(8, 8, 1),
                "register_tile" to listOf(4, 4),
                "dispatch" to listOf((shape[0] + 31) / 32, (shape[1] + 31) / 32, 1),
                "packing_workgroup" to listOf(64, 1, 1),
                "specialization_constants" to emptyList(),
            )) {
            val values = input.getJSONArray(key)
            require(values.length() == expected.size)
            for (i in expected.indices) require(
                values.get(i) is Number && values.getDouble(i) == expected[i].toDouble()
            )
            record.put(key, JSONArray(expected))
        }
        for (key in
            listOf(
                "resident_bytes",
                "scratch_bytes",
                "control_resident_bytes",
                "pipeline_setup_ms",
                "setup_ms",
                "weight_pack_wall_ms",
                "source_rounding_max_abs",
                "host_reference_bytes",
            )) {
            record.put(key, boundedNumber(input, key, 0.0, 1e12))
        }
        for (key in
            listOf(
                "candidate_vendor_id",
                "candidate_device_id",
                "candidate_driver_version",
                "timestamp_valid_bits",
                "candidate_memory_flags_all",
            )) {
            val value =
                boundedNumber(
                    input,
                    key,
                    0.0,
                    if (key == "timestamp_valid_bits") 65.0 else 4_294_967_296.0,
                )
            require(value == value.toLong().toDouble())
            record.put(key, value.toLong())
        }
        val memoryFlags = record.getLong("candidate_memory_flags_all")
        require((memoryFlags and 6L) == 6L)
        require(input.get("candidate_all_memory_device_local") is Boolean)
        val deviceLocal = input.getBoolean("candidate_all_memory_device_local")
        require(deviceLocal == ((memoryFlags and 1L) != 0L))
        record.put("candidate_all_memory_device_local", deviceLocal)
        record.put("timestamp_period_ns", boundedNumber(input, "timestamp_period_ns", 0.0, 1e9))
        for (key in listOf("candidate_device", "control_device")) {
            val value = input.getString(key)
            require(value.length <= 64 && value.matches(Regex("[A-Za-z0-9 ._\\-]*")))
            record.put(key, value)
        }
        if (records.length() > 0) {
            val first = records.getJSONObject(0)
            for (key in
                listOf(
                    "candidate_device",
                    "control_device",
                    "candidate_vendor_id",
                    "candidate_device_id",
                    "candidate_driver_version",
                    "timestamp_valid_bits",
                    "timestamp_period_ns",
                )) require(record.get(key) == first.get(key))
        }
        if (input.has("sampled_peak_pss_kib"))
            record.put(
                "sampled_peak_pss_kib",
                boundedNumber(input, "sampled_peak_pss_kib", -1.0, 1e12),
            )
        for (key in
            listOf(
                "relative_rmse",
                "max_abs_error",
                "control_relative_rmse",
                "control_max_abs_error",
                "control_reference_relative_rmse",
            )) {
            record.put(key, boundedNumber(input, key, -1.0, 1e12))
        }
        record.put(
            "weight_pack_gpu_ns",
            if (input.has("weight_pack_gpu_ns") && input.isNull("weight_pack_gpu_ns"))
                JSONObject.NULL
            else boundedNumber(input, "weight_pack_gpu_ns", 0.0, 1e18),
        )
        for (key in
            listOf(
                "wall_ms",
                "process_cpu_ms",
                "activation_pack_wall_ms",
                "activation_pack_gpu_ns",
                "compute_gpu_ns",
                "boundary_gpu_ns",
                "readback_check_ms",
                "control_wall_ms",
                "control_boundary_gpu_ns",
            )) {
            val source = input.getJSONArray(key)
            require(source.length() == 6)
            val values = JSONArray()
            for (i in 0..5) {
                if (key.endsWith("_ns") && source.isNull(i)) values.put(JSONObject.NULL)
                else {
                    require(source.get(i) is Number)
                    val value = source.getDouble(i)
                    require(
                        value.isFinite() &&
                            value >= 0 &&
                            value < if (key.endsWith("_ns")) 1e18 else 1e9
                    )
                    values.put(value)
                }
            }
            record.put(key, values)
        }
        for (key in listOf("control_counts", "control_shaders")) record.put(
            key,
            measurementMap(input.getJSONObject(key)),
        )
        record.put("environment", environment())
        records.put(record)
        row.put("phase", "PACKED ${records.length()}/4")
    }

    private fun boundedNumber(source: JSONObject, key: String, min: Double, max: Double): Double {
        require(source.get(key) is Number)
        return source.getDouble(key).also { require(it.isFinite() && it >= min && it < max) }
    }

    private fun capability(source: JSONObject, opencl: Boolean): JSONObject {
        val api = if (opencl) "opencl" else "vulkan"
        require(source.getString("api") == api)
        val statuses =
            if (opencl)
                setOf(
                    "cancelled",
                    "library_unavailable_in_app",
                    "symbols_unavailable",
                    "platform_query_failed",
                    "platform_limit_exceeded",
                    "device_query_failed",
                    "device_limit_exceeded",
                    "gpu_with_compiler_unavailable",
                    "context_failed",
                    "queue_failed",
                    "buffer_failed",
                    "program_failed",
                    "compile_failed",
                    "kernel_failed",
                    "argument_failed",
                    "enqueue_failed",
                    "finish_failed",
                    "read_failed",
                    "verification_failed",
                    "verified",
                )
            else
                setOf(
                    "cancelled",
                    "properties2_unavailable",
                    "extension_query_failed",
                    "version_query_failed",
                    "instance_failed",
                    "device_query_failed",
                    "gpu_unavailable",
                    "not_built",
                    "queried",
                )
        val status = source.getString("status").also { require(it in statuses) }
        val result = JSONObject().put("api", api).put("status", status)
        val booleans =
            if (opencl)
                listOf(
                    "verified",
                    "gpuSelected",
                    "extensionsKnown",
                    "fp16Extension",
                    "integerDotExtension",
                    "matrixMultiplyExtension",
                    "fp16ConfigKnown",
                    "fp16Arithmetic",
                )
            else
                listOf(
                    "int8QueryAvailable",
                    "dotQueryAvailable",
                    "shaderInt8",
                    "shaderFloat16",
                    "shaderIntegerDotProduct",
                    "integerDotProduct8BitSignedAccelerated",
                    "integerDotProduct8BitUnsignedAccelerated",
                    "integerDotProduct8BitMixedSignednessAccelerated",
                    "integerDotProduct4x8BitPackedSignedAccelerated",
                    "integerDotProduct4x8BitPackedUnsignedAccelerated",
                    "integerDotProduct4x8BitPackedMixedSignednessAccelerated",
                    "integerDotProductAccumulatingSaturating4x8BitPackedSignedAccelerated",
                )
        for (key in booleans) if (source.has(key)) {
            require(source.get(key) is Boolean)
            result.put(key, source.getBoolean(key))
        }
        if (opencl) {
            require(
                result.has("verified") && result.getBoolean("verified") == (status == "verified")
            )
            if (result.has("gpuSelected")) require(result.getBoolean("gpuSelected"))
            if (status == "verified") require(result.optBoolean("gpuSelected"))
        }
        for (key in
            if (opencl) listOf("deviceName", "deviceVendor", "driverVersion")
            else listOf("deviceName")) if (source.has(key)) {
            val value = source.getString(key)
            require(value.length <= 80 && value.matches(Regex("[A-Za-z0-9 ._()\\-]*")))
            result.put(key, value)
        }
        if (opencl)
            for (key in
                listOf("maxWorkGroupSize", "localMemoryBytes", "compileUs", "executeUs")) if (
                source.has(key)
            ) {
                val value =
                    boundedNumber(
                        source,
                        key,
                        0.0,
                        if (key.endsWith("Us")) 1e12 else 2_147_483_648.0,
                    )
                require(value == value.toLong().toDouble())
                result.put(key, value.toLong())
            }
        return result
    }

    @Synchronized
    fun endCase(status: String, failure: String? = null) {
        current?.let {
            if (it.optString("status") == "RUNNING") it.put("status", status)
            if (failure != null && !it.has("failure")) it.put("failure", failure)
            it.put("after", environment())
        }
        save(relaySnapshot = true)
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
        save(relaySnapshot = true)
    }

    @Synchronized
    fun clear() {
        relay.disconnect()
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

    private fun save(relaySnapshot: Boolean = false) {
        if (relay.connected && report.has("schema")) report.put("live_relay_enabled", true)
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
        if (relaySnapshot && report.has("schema")) relay.offer(bytes)
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
                        if (row.optInt("variant") == 70) {
                            val capabilities = row.optJSONObject("capabilities")
                            appendLine(
                                "${row.getString("model")} · ${row.getString("id")} · ${row.optString("status")} · OpenCL ${capabilities?.optJSONObject("opencl")?.optString("status") ?: "pending"} · Vulkan ${capabilities?.optJSONObject("vulkan")?.optString("status") ?: "pending"}${if(row.has("failure")) " · ${row.getString("failure")}" else ""}"
                            )
                            continue
                        }
                        if (row.optInt("variant") in 60..63) {
                            val packed = row.optJSONArray("packed")
                            append(
                                "${row.getString("model")} · ${row.getString("id")} · ${packed?.length() ?: 0}/4 packed shapes · ${row.optString("status")}${if(row.has("failure")) " · ${row.getString("failure")}" else ""}"
                            )
                            if (
                                packed != null &&
                                    (0 until packed.length()).any {
                                        val item = packed.getJSONObject(it)
                                        !item.getBoolean("finite") ||
                                            item.getDouble("relative_rmse") > 0.0001 ||
                                            item.getDouble("control_relative_rmse") > 0.002 ||
                                            item.getDouble("control_reference_relative_rmse") >
                                                0.002
                                    }
                            )
                                append(" · CHECK NUMERICS")
                            appendLine()
                            continue
                        }
                        val kernels = row.optJSONArray("kernels")
                        if (EncoderExperiment.isKernel(row.optInt("variant"))) {
                            append(
                                "${row.getString("model")} · ${row.getString("id")} · ${kernels?.length() ?: 0}/${EncoderExperiment.kernelCount(row.optInt("variant"))} kernels · ${row.optString("status")}${if(row.has("failure")) " · ${row.getString("failure")}" else ""}"
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
