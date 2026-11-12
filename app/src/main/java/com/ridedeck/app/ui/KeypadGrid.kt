package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared keypad geometry. The 6x2 variant is only chosen when its width and height keep every
 * key at least [KEYPAD_MIN_TARGET]; callers measure the real space instead of trusting a modifier
 * minimum that a short weighted row can override.
 */
internal val KEYPAD_3X4 = listOf("123", "456", "789", "*0#")
internal val KEYPAD_6X2 = listOf("123456", "789*0#")
internal val KEYPAD_GAP = 8.dp
internal val KEYPAD_MIN_TARGET = 64.dp
internal val KEYPAD_3X4_HEIGHT = KEYPAD_MIN_TARGET * 4 + KEYPAD_GAP * 3
internal val KEYPAD_6X2_HEIGHT = KEYPAD_MIN_TARGET * 2 + KEYPAD_GAP
internal val KEYPAD_3X4_WIDTH = KEYPAD_MIN_TARGET * 3 + KEYPAD_GAP * 2
internal val KEYPAD_6X2_WIDTH = KEYPAD_MIN_TARGET * 6 + KEYPAD_GAP * 5

/** Flat digit order; fallback rows reflow it so narrow screens never squeeze a key under 64 dp. */
internal val KEYPAD_DIGITS = "123456789*0#"

/** Columns that keep every key at least [KEYPAD_MIN_TARGET] wide in [availableWidth]. */
internal fun keypadFallbackColumns(availableWidth: Dp): Int =
    ((availableWidth + KEYPAD_GAP) / (KEYPAD_MIN_TARGET + KEYPAD_GAP)).toInt().coerceIn(1, 3)

internal fun keypadFallbackRows(availableWidth: Dp): List<String> =
    KEYPAD_DIGITS.chunked(keypadFallbackColumns(availableWidth))

internal fun keypadRowsHeight(rowCount: Int): Dp =
    KEYPAD_MIN_TARGET * rowCount + KEYPAD_GAP * (rowCount - 1)

/** Lays [rows] out to fill [modifier]'s measured size; each row is one equal share. */
@Composable
internal fun KeypadGrid(
    rows: List<String>,
    modifier: Modifier = Modifier,
    key: @Composable (Char) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KEYPAD_GAP)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KEYPAD_GAP),
            ) {
                row.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight()) { key(c) } }
            }
        }
    }
}
