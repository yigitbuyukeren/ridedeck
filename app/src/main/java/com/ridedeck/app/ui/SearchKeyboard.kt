package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import java.util.Locale

/** What the on-screen keyboard types: Turkish letters, a number and symbol page, and the casing rules. */
object KeyboardLayout {
    val letters = listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç")
    val symbols = listOf("1234567890", "-/:;()&@\"'", ".,?!+=*#%")
    private val turkish = Locale("tr", "TR")

    /** Turkish casing: i becomes İ and ı becomes I. */
    fun cased(key: String, shift: Boolean): String = if (shift) key.uppercase(turkish) else key

    fun append(text: String, key: String, shift: Boolean, max: Int = 80): String =
        if (text.length >= max) text else text + cased(key, shift)

    fun backspace(text: String): String = text.dropLast(1)

    /** Capitalise at the start of the text and after a space, like any keyboard. */
    fun autoShift(text: String): Boolean = text.isEmpty() || text.endsWith(" ")
}

/**
 * Keyboard drawn by the app itself, so it can be made for a gloved hand and a landscape screen: big
 * keys across the whole width, the search button always in view, nothing resized behind it.
 */
@Composable
fun SearchKeyboard(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var symbols by remember { mutableStateOf(false) }
    var shiftOverride by remember { mutableStateOf<Boolean?>(null) }
    val shift = shiftOverride ?: KeyboardLayout.autoShift(query)
    val rows = if (symbols) KeyboardLayout.symbols else KeyboardLayout.letters

    fun type(key: String) {
        onQuery(KeyboardLayout.append(query, key, shift && !symbols))
        shiftOverride = null
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xF2101114), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().height(KEY_HEIGHT)) {
                if (index == 2 && !symbols) {
                    Key(Modifier.weight(1.5f), onClick = { shiftOverride = !shift }, highlight = shift) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.key_shift), tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
                row.forEach { c ->
                    Key(Modifier.weight(1f), onClick = { type(c.toString()) }) { KeyLabel(KeyboardLayout.cased(c.toString(), shift && !symbols)) }
                }
                if (index == 2) {
                    // Tap erases one character, a long press the whole text.
                    Key(Modifier.weight(1.5f), onClick = { onQuery(KeyboardLayout.backspace(query)); shiftOverride = null }, onLongClick = { onQuery("") }) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.key_backspace), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().height(KEY_HEIGHT)) {
            Key(Modifier.weight(1.4f), onClick = { symbols = !symbols }) { KeyLabel(if (symbols) "ABC" else "123", size = 20) }
            Key(Modifier.weight(1f), onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.key_hide), tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Key(Modifier.weight(3.4f), onClick = { type(" ") }) { Box(Modifier.fillMaxWidth(0.5f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))) }
            Key(Modifier.weight(2.2f), onClick = onSearch, color = MotoColors.Blue) {
                Text(stringResource(R.string.search), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

private val KEY_HEIGHT = 54.dp

@Composable
private fun KeyLabel(text: String, size: Int = 26) {
    Text(text, fontSize = size.sp, fontWeight = FontWeight.Medium, color = Color.White)
}

@Composable
private fun Key(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    color: Color = Color(0xFF2C2C2E),
    highlight: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(if (highlight) MotoColors.Blue else color)
            .pressKey(enabled = true, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
