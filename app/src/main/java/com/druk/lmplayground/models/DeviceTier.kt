package com.druk.lmplayground.models

import android.os.Build
import java.io.File

/**
 * What this phone can run, from its RAM and chip, as PrivateLM sizes it
 * (DeviceInfoService / device_info_native.dart): a tier, the context and
 * reply length to start with, the largest that's safe, and CPU threads.
 */
data class DeviceTier(
    val totalRamGb: Double,
    val availableRamGb: Double,
    val tier: Tier,
    val chip: Chip,
    /** The chip as Android reports it, e.g. "SM8550" or "gs201". */
    val hardware: String,
) {
    enum class Tier { LOW, MID, HIGH, ULTRA }

    enum class Chip(val label: String, val recommendedQuant: String) {
        SNAPDRAGON("Snapdragon", "Q4_K_M"),
        MEDIATEK("MediaTek", "Q4_K_M"),
        EXYNOS("Exynos", "Q4_K_M"),
        GOOGLE_TENSOR("Google Tensor", "Q4_0 or Q5_K_M, not Q4_K_M"),
        UNISOC("Unisoc", "Q4_K_M"),
        ROCKCHIP("Rockchip", "Q4_K_M"),
        HISILICON("Kirin", "Q4_K_M"),
        UNKNOWN("Unknown", "Q4_K_M"),
    }

    private val limits: IntArray
        get() = when {
            // context, reply length, largest safe context, largest safe reply
            totalRamGb <= 4 -> intArrayOf(1024, 256, 2048, 512)
            totalRamGb <= 6 -> intArrayOf(2048, 512, 4096, 1024)
            totalRamGb <= 8 -> intArrayOf(4096, 1024, 8192, 2048)
            totalRamGb <= 12 -> intArrayOf(4096, 2048, 8192, 4096)
            else -> intArrayOf(8192, 4096, 16384, 4096)
        }

    val recommendedContextSize get() = limits[0]
    val recommendedMaxTokens get() = limits[1]
    val maxSafeContextSize get() = limits[2]
    val maxSafeTokens get() = limits[3]

    /**
     * CPU threads for a model, by tier (6/5/4/3). One thread for Gemma on
     * Google Tensor: PrivateLM found Q4_K_M dequantization corrupts Gemma's
     * output there with more than one. On the GPU the engine uses 4.
     */
    fun cpuThreads(modelFilename: String?): Int {
        if (chip == Chip.GOOGLE_TENSOR && modelFilename.orEmpty().lowercase().contains("gemma")) return 1
        return when (tier) {
            Tier.ULTRA -> 6
            Tier.HIGH -> 5
            Tier.MID -> 4
            Tier.LOW -> 3
        }
    }

    /** Warning to show for this chip's models, or null. */
    val quantWarning: String?
        get() = if (chip == Chip.GOOGLE_TENSOR) {
            "Google Tensor has a known bug with Q4_K_M models that gives empty or garbled replies. Use Q4_0 or Q5_K_M models instead."
        } else null

    companion object {
        private const val GB = 1024.0 * 1024 * 1024

        /** Reads /proc/meminfo and the chip name. Cheap; call when needed. */
        fun read(): DeviceTier {
            var total = 4.0
            var available = 2.0
            try {
                val meminfo = File("/proc/meminfo").readText()
                Regex("""MemTotal:\s+(\d+)""").find(meminfo)?.let { total = it.groupValues[1].toLong() * 1024 / GB }
                Regex("""MemAvailable:\s+(\d+)""").find(meminfo)?.let { available = it.groupValues[1].toLong() * 1024 / GB }
            } catch (_: Exception) {
            }
            val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (_: Exception) { "" }
            val hardware = listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } else null,
                Build.HARDWARE.takeIf { it.isNotBlank() },
                Regex("""Hardware\s*:\s*(.+)""", RegexOption.IGNORE_CASE).find(cpuinfo)?.groupValues?.get(1)?.trim(),
            ).distinct().joinToString(" ")
            val tier = when {
                total <= 4 -> Tier.LOW
                total <= 6 -> Tier.MID
                total <= 8 -> Tier.HIGH
                else -> Tier.ULTRA
            }
            return DeviceTier(total, available, tier, detectChip(cpuinfo, hardware), hardware)
        }

        /** PrivateLM's chip detection (_detectSocFamily). */
        fun detectChip(cpuinfo: String, hardware: String): Chip {
            val c = cpuinfo.lowercase()
            val h = hardware.lowercase()
            return when {
                Regex("""gs\d{3}""").containsMatchIn(h) || c.contains("google tensor") || h.contains("tensor") -> Chip.GOOGLE_TENSOR
                h.contains("qcom") || h.contains("qualcomm") || h.contains("snapdragon") ||
                    Regex("""\bsm\d{4,}""").containsMatchIn(h) || c.contains("snapdragon") -> Chip.SNAPDRAGON
                h.contains("mt6") || h.contains("mt8") || c.contains("mediatek") ||
                    c.contains("dimensity") || c.contains("helio") -> Chip.MEDIATEK
                h.contains("exynos") || h.contains("s5e") || c.contains("exynos") -> Chip.EXYNOS
                h.contains("unisoc") || h.contains("spreadtrum") || Regex("""\b(sc|ums|t)\d{3,4}""").containsMatchIn(h) -> Chip.UNISOC
                h.contains("rockchip") || Regex("""\brk\d""").containsMatchIn(h) -> Chip.ROCKCHIP
                h.contains("hisilicon") || h.contains("kirin") || c.contains("kirin") || c.contains("hisi") -> Chip.HISILICON
                else -> Chip.UNKNOWN
            }
        }
    }
}
