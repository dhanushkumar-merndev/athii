package com.oki

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oki.core.storage.Appearance
import com.oki.core.ui.OkiTheme
import com.oki.feature.doctors.DoctorFiltersRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DoctorFiltersTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun equalSingleRowAtNarrowWidthWithLargeTextAndWorkingDropdowns() {
        var attendance by mutableStateOf("All")
        var department by mutableStateOf("")
        var day by mutableStateOf("")
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                OkiTheme(Appearance.DARK) {
                    Box(Modifier.width(276.dp)) {
                        DoctorFiltersRow(
                            attendance,
                            department,
                            day,
                            listOf("Cardiology and internal medicine"),
                            { attendance = it },
                            { department = it },
                            { day = it },
                        )
                    }
                }
            }
        }
        val tags = listOf("Attendance", "Department", "Working day").map { "doctor-filter-$it" }
        val bounds = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        bounds.drop(1).forEach {
            assertEquals(bounds[0].top, it.top, 1f)
            assertEquals(bounds[0].width, it.width, 1f)
        }
        assertTrue(bounds[0].right < bounds[1].left)
        assertTrue(bounds[1].right < bounds[2].left)
        rule.onNodeWithTag(tags[0]).performClick()
        rule.onNodeWithText("Absent").performClick()
        assertEquals("Absent", attendance)
        rule.onNodeWithTag(tags[0]).performClick()
        rule.onNodeWithText("Present").performClick()
        assertEquals("Present", attendance)
        rule.onNodeWithTag(tags[0]).performClick()
        rule.onNodeWithText("All", substring = false).performClick()
        assertEquals("All", attendance)
        rule.onNodeWithTag(tags[1]).performClick()
        rule.onNodeWithText("Cardiology and internal medicine").performClick()
        assertEquals("Cardiology and internal medicine", department)
        rule.onNodeWithTag(tags[2]).performClick()
        rule.onNodeWithText("Tuesday").performClick()
        assertEquals("TUESDAY", day)
        tags.forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
    }
}
