package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionsTest {
    private val recommended = setOf("ru.rostel", "ru.oneme.app")

    @Test
    fun recommendedAppsAreExcludedUnlessOverridden() {
        val settings = ExclusionSettings(manual = setOf("com.bank"), recommended = recommended, useRecommended = true, overrides = setOf("ru.oneme.app"))
        assertEquals(setOf("com.bank", "ru.rostel"), settings.effective)
        assertTrue(settings.isRecommended("ru.oneme.app"))
    }

    @Test
    fun switchTurnsOffTheWholeList() {
        val settings = ExclusionSettings(manual = setOf("com.bank"), recommended = recommended, useRecommended = false, overrides = emptySet())
        assertEquals(setOf("com.bank"), settings.effective)
        assertFalse(settings.isRecommended("ru.rostel"))
    }
}
