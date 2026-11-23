package com.tztvhelper.app.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 型号匹配：型号+版本 > 型号 > 品牌通用；REJECTED 永不参与 */
class MatchPickerTest {

    private fun method(
        id: String,
        modelId: String? = null,
        scope: String = if (modelId == null) "brand" else "model",
        osMin: String? = null,
        osMax: String? = null,
        status: MethodStatus = MethodStatus.CANDIDATE,
        success: Int = 0
    ) = Method(
        id = id,
        brandId = "xiaomi",
        modelId = modelId,
        title = id,
        scope = scope,
        osMin = osMin,
        osMax = osMax,
        status = status,
        successCount = success
    )

    private val g = method("brand-generic")
    private val a = method("mi-4a-8", modelId = "mi-4a", osMin = "8.0", osMax = "9.9")
    private val aNoOs = method("mi-4a-any", modelId = "mi-4a")
    private val c = method("mi-4c", modelId = "mi-4c")

    @Test
    fun `型号和系统版本都对上时是 EXACT_MODEL_OS`() {
        val p = MatchPicker.pick(listOf(g, a), "mi-4a", "8.1")
        assertEquals(MatchLevel.EXACT_MODEL_OS, p!!.level)
        assertEquals("mi-4a-8", p.method.id)
    }

    @Test
    fun `型号对上但没有版本记录时是 MODEL`() {
        val p = MatchPicker.pick(listOf(g, aNoOs), "mi-4a", "8.1")
        assertEquals(MatchLevel.MODEL, p!!.level)
        assertEquals("mi-4a-any", p.method.id)
    }

    @Test
    fun `没有型号方法时退回品牌通用，并标明是通用`() {
        val p = MatchPicker.pick(listOf(g), null, "8.1")
        assertEquals(MatchLevel.BRAND_GENERIC, p!!.level)
        assertEquals("brand-generic", p.method.id)
    }

    @Test
    fun `小米 4A 与 4C 的教程不会互相串用`() {
        val p = MatchPicker.pick(listOf(a, c), "mi-4c", "8.1")
        assertEquals("mi-4c", p!!.method.id)

        // 只有 4A 的资料时，查 4C 不能把 4A 的方法给它
        assertNull(MatchPicker.pick(listOf(a), "mi-4c", "8.1"))
    }

    @Test
    fun `系统版本超出方法范围时不当成匹配`() {
        // 这条方法只覆盖 8.x，用户的电视是 11，不能硬套
        assertNull(MatchPicker.pick(listOf(a), "mi-4a", "11"))
        val fallback = MatchPicker.pick(listOf(g, a), "mi-4a", "11")
        assertEquals(MatchLevel.BRAND_GENERIC, fallback!!.level)
    }

    @Test
    fun `只有已否决方法时返回未找到`() {
        val rejected = method("bad", modelId = "mi-4a", status = MethodStatus.REJECTED)
        assertNull(MatchPicker.pick(listOf(rejected), "mi-4a", "8.1"))
        assertNull(MatchPicker.pick(listOf(rejected), null, null))
    }

    @Test
    fun `已否决方法不会压过候选方法`() {
        val rejected = method("bad", modelId = "mi-4a", status = MethodStatus.REJECTED, success = 9)
        val good = method("ok", modelId = "mi-4a", status = MethodStatus.CANDIDATE)
        assertEquals("ok", MatchPicker.pick(listOf(rejected, good), "mi-4a", null)!!.method.id)
    }

    @Test
    fun `品牌通用方法的系统范围也要遵守`() {
        val gOld = method("generic-8", osMin = "8.0", osMax = "9.9")
        assertNull(MatchPicker.pick(listOf(gOld), null, "11"))
    }
}

/** 自由输入 → 库内型号 ID，不允许乱猜 */
class ModelMatcherTest {

    private val models = listOf(
        TvModel("mi-4a", "xiaomi", "4A", "小米电视 4A"),
        TvModel("mi-4c", "xiaomi", "4C", "小米电视 4C")
    )

    @Test
    fun `带品牌名的输入也能精确命中`() {
        assertEquals("mi-4a", ModelMatcher.match(models, "小米4A", "小米")!!.id)
        assertEquals("mi-4c", ModelMatcher.match(models, "小米 4C", "小米")!!.id)
    }

    @Test
    fun `只写型号也能命中`() {
        assertEquals("mi-4c", ModelMatcher.match(models, "4c", "小米")!!.id)
    }

    @Test
    fun `型号不同不会误配`() {
        assertNull(ModelMatcher.match(models, "小米4X", "小米"))
        assertNull(ModelMatcher.match(models, "小米4", "小米"))
    }

    @Test
    fun `空输入返回空`() {
        assertNull(ModelMatcher.match(models, "", "小米"))
        assertNull(ModelMatcher.match(emptyList(), "4A", "小米"))
    }
}
