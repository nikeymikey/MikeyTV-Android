package com.github.damontecres.wholphin.mikeytv

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/** Lets the nav drawer and player controls reach the message center without new wiring. */
val LocalMessageCenter = staticCompositionLocalOf<MessageCenter?> { null }

private val Navy = Color(0xFF120A2E)
private val Pink = Color(0xFFFF2FA8)
private val PinkLight = Color(0xFFFF7AC6)
private val Cyan = Color(0xFF19D3FF)
private val Violet = Color(0xFF8A3FFC)
private val Gold = Color(0xFFFFC526)

/** Toast and reply panel, drawn above every page including playback. */
@Composable
fun MessageOverlay(messageCenter: MessageCenter) {
    val toast by messageCenter.toast.collectAsState()
    val panelOpen by messageCenter.panelOpen.collectAsState()

    // Not focusable and not clickable, so it never takes the remote away from the page.
    toast?.let { message ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp, end = 32.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 420.dp)
                        .background(Navy.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
                        .border(2.dp, Pink, RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (message.header.isNotBlank()) {
                    Text(
                        text = message.header,
                        color = PinkLight,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = message.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.mikeytv_message_reply_hint),
                    color = Cyan,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (panelOpen) {
        MessagePanel(messageCenter)
    }
}

/**
 * Side panel in its own dialog window, so it takes the D-pad from any page and
 * Back closes it. Playback keeps running because the activity is not paused.
 */
@Composable
private fun MessagePanel(messageCenter: MessageCenter) {
    val messages by messageCenter.messages.collectAsState()
    val replySupported by messageCenter.replySupported.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val newest = messages.firstOrNull()

    val sendingText = stringResource(R.string.mikeytv_message_sending)
    val sentText = stringResource(R.string.mikeytv_message_sent)
    val failedText = stringResource(R.string.mikeytv_message_send_failed)

    fun send(text: String) {
        if (sending || newest == null || text.isBlank()) return
        sending = true
        status = sendingText
        scope.launch {
            try {
                messageCenter.sendReply(newest.id, text)
                status = sentText
                replyText = ""
                delay(1200.milliseconds)
                messageCenter.closePanel()
            } catch (ex: Exception) {
                Timber.w(ex, "Reply failed")
                status = failedText
            } finally {
                sending = false
            }
        }
    }

    Dialog(
        onDismissRequest = { messageCenter.closePanel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.let { window ->
            window.setGravity(Gravity.END)
            window.setDimAmount(0f)
        }
        LaunchedEffect(replySupported) { firstFocus.tryRequestFocus() }

        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = 360.dp, max = 420.dp)
                    .padding(vertical = 24.dp, horizontal = 16.dp)
                    .background(Navy, RoundedCornerShape(16.dp))
                    .border(2.dp, Violet, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mikeytv_messages),
                color = Cyan,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages.take(5), key = { it.id }) { message ->
                    MessageItem(message, isNewest = message.id == newest?.id)
                }
            }
            status?.let {
                Text(text = it, color = Gold, style = MaterialTheme.typography.bodyMedium)
            }
            when (replySupported) {
                true -> {
                    listOf(
                        R.string.mikeytv_quick_got_it,
                        R.string.mikeytv_quick_thanks,
                        R.string.mikeytv_quick_not_now,
                    ).forEachIndexed { index, res ->
                        val label = stringResource(res)
                        PanelButton(
                            text = label,
                            onClick = { send(label) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                    EditTextBox(
                        value = replyText,
                        onValueChange = { replyText = it.take(MessageCenter.MAX_REPLY_LENGTH) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(replyText) }),
                        placeholder = { Text(stringResource(R.string.mikeytv_type_reply)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PanelButton(
                        text = stringResource(R.string.mikeytv_close),
                        onClick = { messageCenter.closePanel() },
                    )
                }

                false -> {
                    Text(
                        text = stringResource(R.string.mikeytv_replies_unavailable),
                        color = Gold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PanelButton(
                        text = stringResource(R.string.mikeytv_close),
                        onClick = { messageCenter.closePanel() },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }

                null -> {
                    // Still checking. Close is always available.
                    PanelButton(
                        text = stringResource(R.string.mikeytv_close),
                        onClick = { messageCenter.closePanel() },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: AdminMessage,
    isNewest: Boolean,
) {
    val alpha = if (isNewest) 1f else 0.65f
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.header.isNotBlank()) {
            Text(
                text = message.header,
                color = PinkLight.copy(alpha = alpha),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            text = message.text,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyLarge,
        )
        message.reply?.let {
            Text(
                text = stringResource(R.string.mikeytv_you_replied, it),
                color = Cyan.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.colors(
                containerColor = Violet.copy(alpha = 0.25f),
                contentColor = Color.White,
                focusedContainerColor = Pink,
                focusedContentColor = Color.White,
            ),
    ) {
        Text(text = text)
    }
}
