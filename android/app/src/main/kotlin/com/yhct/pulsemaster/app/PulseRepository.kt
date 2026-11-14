package com.yhct.pulsemaster.app

import android.content.Context
import com.yhct.pulsemaster.core.CalibrationException
import com.yhct.pulsemaster.core.FingerCalibration
import com.yhct.pulsemaster.core.PulseCatalog
import com.yhct.pulsemaster.core.PulseDataException
import java.io.IOException

/** Kết quả nạp dữ liệu: hoặc có catalog, hoặc có thông báo lỗi để hiển thị rõ ràng. */
sealed class CatalogResult {
    data class Ok(val catalog: PulseCatalog) : CatalogResult()
    data class Error(val message: String) : CatalogResult()
}

object PulseRepository {
    const val ASSET = "tcm_28_pulses_haptic.json"

    @Volatile private var cached: CatalogResult? = null

    fun load(context: Context): CatalogResult {
        cached?.let { return it }
        val r = try {
            val text = context.applicationContext.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            CatalogResult.Ok(PulseCatalog.parse(text))
        } catch (e: IOException) {
            CatalogResult.Error("Không đọc được $ASSET: ${e.message}")
        } catch (e: PulseDataException) {
            CatalogResult.Error("Dữ liệu mạch không hợp lệ: ${e.message}")
        }
        if (r is CatalogResult.Ok) cached = r
        return r
    }
}

/** Lưu hiệu chuẩn riêng cho thiết bị + ngón tay. Giá trị lưu hỏng thì quay về mặc định, không crash. */
class CalibrationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulsemaster", Context.MODE_PRIVATE)

    fun load(): FingerCalibration {
        val json = prefs.getString(KEY, null) ?: return default()
        return try {
            FingerCalibration.fromJson(json)
        } catch (e: CalibrationException) {
            default()
        } catch (e: PulseDataException) {
            default()
        }
    }

    fun isCalibrated(): Boolean = prefs.contains(KEY)

    fun save(c: FingerCalibration) { prefs.edit().putString(KEY, c.toJson()).apply() }

    fun clear() { prefs.edit().remove(KEY).apply() }

    /** Mặc định theo dải size phổ biến (0.03–0.14) trước khi người dùng hiệu chuẩn. */
    private fun default() = FingerCalibration.defaultSizeOnly(0.03, 0.085, 0.14)

    private companion object { const val KEY = "finger_cal_v1" }
}
