package com.keymapper

import com.keymapper.util.ResolutionScaler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分辨率等比缩放单元测试（P1-4）。
 *
 * 只验证纯函数换算逻辑；[ResolutionScaler.realScreenSize] 依赖 Android 运行时，不在本测试覆盖。
 */
class ResolutionScalerTest {

    private val scaler = ResolutionScaler()

    @Test
    fun scaleValue_basic() {
        // 1080 -> 2160（2 倍）
        assertEquals(200, scaler.scaleValue(100, 1080, 2160))
    }

    @Test
    fun scaleValue_rounding() {
        // 100 * 1440 / 1080 = 133.33 -> 四舍五入 133
        assertEquals(133, scaler.scaleValue(100, 1080, 1440))
    }

    @Test
    fun scaleValue_sameSize_noChange() {
        assertEquals(100, scaler.scaleValue(100, 1080, 1080))
    }

    @Test
    fun scaleValue_invalidSource_returnsOriginal() {
        assertEquals(100, scaler.scaleValue(100, 0, 2160))
    }

    @Test
    fun scalePoint() {
        // 源 1080x1920，目标 1440x2560
        val p = scaler.scalePoint(100, 150, 1080, 1920, 1440, 2560)
        assertEquals(133, p.x)
        assertEquals(200, p.y)
    }

    @Test
    fun needsScaling() {
        assertEquals(false, scaler.needsScaling(1080, 1920, 1080, 1920))
        assertEquals(true, scaler.needsScaling(1080, 1920, 1440, 2560))
        assertEquals(false, scaler.needsScaling(0, 0, 1440, 2560))
    }
}
