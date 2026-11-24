package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** UI U2: what the playlist menu can ask for. Rename and Delete are only offered for owned playlists. */
internal enum class DesktopPlaylistAction { PLAY, SHUFFLE, PLAY_NEXT, ADD_TO_QUEUE, RENAME, DELETE }

/**
 * The right-click / long-press menu of a playlist card. It is a small centred card, because the
 * shared Library cards report the click but not where it was.
 */
@Composable
internal fun DesktopPlaylistMenu(
    title: String,
    pinned: Boolean,
    canEdit: Boolean,
    onAction: (DesktopPlaylistAction) -> Unit,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(300.dp)
                .background(Color(0xFF242428), RoundedCornerShape(14.dp))
                .padding(vertical = 8.dp),
        ) {
            Text(
                title,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            @Composable
            fun row(label: String, click: () -> Unit) = Text(
                label,
                Modifier.fillMaxWidth().clickable { click(); onDismiss() }.padding(horizontal = 16.dp, vertical = 11.dp),
                color = Color.White.copy(alpha = 0.9f),
            )
            row("Play") { onAction(DesktopPlaylistAction.PLAY) }
            row("Shuffle") { onAction(DesktopPlaylistAction.SHUFFLE) }
            row("Play next") { onAction(DesktopPlaylistAction.PLAY_NEXT) }
            row("Add to queue") { onAction(DesktopPlaylistAction.ADD_TO_QUEUE) }
            row(if (pinned) "Unpin" else "Pin to top") { onTogglePin() }
            if (canEdit) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                row("Rename") { onAction(DesktopPlaylistAction.RENAME) }
                row("Delete") { onAction(DesktopPlaylistAction.DELETE) }
            }
        }
    }
}
