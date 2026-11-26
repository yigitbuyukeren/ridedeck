package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.service.IncomingMessage
import com.ridedeck.app.util.simplifyMessage
import kotlinx.coroutines.delay

private const val AUTO_DISMISS_MS = 25_000L

/** Big, high-contrast card for an incoming chat message. Always dark so it reads over any theme. */
@Composable
fun MessageBanner(
    message: IncomingMessage,
    onRead: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message.id) {
        delay(AUTO_DISMISS_MS)
        onDismiss()
    }
    val body = simplifyMessage(message.text, stringResource(R.string.message_link))
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF101114))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(26.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message.app,
            fontSize = 14.sp, color = DeckColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (message.sender.isNotBlank()) {
            Text(
                message.sender,
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(body, fontSize = 22.sp, color = Color(0xE6FFFFFF), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(stringResource(R.string.message_read), DeckColors.Blue, onRead, Modifier.weight(1f))
            PillButton(stringResource(R.string.message_dismiss), Color(0xFF2C2C2E), onDismiss, Modifier.weight(1f))
        }
    }
}
