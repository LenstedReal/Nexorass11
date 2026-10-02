package com.lenstedreal.nexorawatch.ui.room

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.lenstedreal.nexorawatch.R
import com.lenstedreal.nexorawatch.data.Room
import com.lenstedreal.nexorawatch.player.NexoraPlayer
import com.lenstedreal.nexorawatch.ui.theme.NexoraBrand
import com.lenstedreal.nexorawatch.ui.theme.NexoraGlassBorder
import com.lenstedreal.nexorawatch.ui.theme.NexoraMuted
import com.lenstedreal.nexorawatch.ui.theme.NexoraOnSurface
import com.lenstedreal.nexorawatch.ui.theme.NexoraSurfaceSecondary

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    room: Room,
    player: NexoraPlayer?,
    isHost: Boolean,
    localVideoName: String?,
    modifier: Modifier = Modifier
) {
    val roomVideo = room.video
    val hasVideo = roomVideo != null || localVideoName != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, NexoraGlassBorder, RoundedCornerShape(14.dp))
            .background(Color.Black)
            .testTag("video_player_container")
    ) {
        if (!hasVideo) {
            // Waiting for video placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = NexoraMuted,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.waiting_for_video),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = NexoraOnSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.waiting_for_video_desc),
                        fontSize = 12.sp,
                        color = NexoraMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    if (player != null) {
                        val playerState by player.state.collectAsState()

                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    this.player = player.exoPlayer
                                    useController = isHost // Only host can scrub/play/pause directly
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (playerState.isBuffering) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = NexoraBrand,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Guest overlay to intercept accidental touches
                        if (!isHost) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                            )
                        }
                    }
                }

                if (localVideoName != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexoraSurfaceSecondary)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.playing_on_device)}: $localVideoName",
                            fontSize = 11.sp,
                            color = NexoraMuted
                        )
                    }
                }
            }
        }
    }
}
