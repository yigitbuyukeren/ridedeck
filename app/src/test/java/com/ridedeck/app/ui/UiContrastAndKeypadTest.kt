package com.ridedeck.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure contrast/geometry helpers used by the phone and call screens; no Android or calls. */
class UiContrastAndKeypadTest {
    @Test fun greenCallActionUsesBlackContentAndOtherActionsKeepWhite() {
        assertEquals(Color.Black, callActionForeground(MotoColors.Green))
        assertEquals(Color.White, callActionForeground(MotoColors.Red))
        assertEquals(Color.White, callActionForeground(MotoColors.Blue))
    }

    @Test fun phoneCallButtonUsesBlackWhenEnabledAndMutedWhenDisabled() {
        assertEquals(Color.Black, phoneCallForeground(enabled = true, muted = MotoColors.Gray))
        assertEquals(MotoColors.Gray, phoneCallForeground(enabled = false, muted = MotoColors.Gray))
    }

    @Test fun keypadFallbackNeverSqueezesAKeyBelow64() {
        listOf(64.dp, 100.dp, 135.dp, 136.dp, 180.dp, 207.dp, 208.dp, 300.dp).forEach { width ->
            val columns = keypadFallbackColumns(width)
            assertTrue("columns for ${width} stay between 1 and 3", columns in 1..3)
            val usable = width - KEYPAD_GAP * (columns - 1)
            assertTrue("key width for $width is at least 64 dp", usable >= 64.dp * columns)
            assertEquals(KEYPAD_DIGITS.length, keypadFallbackRows(width).sumOf { it.length })
        }
    }

    @Test fun keypadFallbackAddsColumnsOnlyWhenWidthAllows() {
        assertEquals(1, keypadFallbackColumns(100.dp))
        assertEquals(2, keypadFallbackColumns(200.dp))
        assertEquals(3, keypadFallbackColumns(208.dp))
        assertEquals(280.dp, keypadRowsHeight(4))
        assertEquals(424.dp, keypadRowsHeight(6))
    }
}
