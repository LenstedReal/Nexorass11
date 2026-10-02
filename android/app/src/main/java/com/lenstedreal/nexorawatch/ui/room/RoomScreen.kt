package com.lenstedreal.nexorawatch.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenstedreal.nexorawatch.R
import com.lenstedreal.nexorawatch.ui.theme.NexoraBorder
import com.lenstedreal.nexorawatch.ui.theme.NexoraBrand
import com.lenstedreal.nexorawatch.ui.theme.NexoraMuted
import com.lenstedreal.nexorawatch.ui.theme.NexoraOnBrand
import com.lenstedreal.nexorawatch.ui.theme.NexoraOnSurface
import com.lenstedreal.nexorawatch.ui.theme.NexoraSurface
import com.lenstedreal.nexorawatch.ui.theme.NexoraSurfaceSecondary

@Composable
fun RoomScreen(
    viewModel: RoomViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    BackHandler {
        viewModel.leaveRoom(onLeft = onNavigateBack)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexoraSurface)
            .testTag("room_screen")
    ) {
        if (uiState.roomLoading && uiState.room == null) {
            // Loading State
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = NexoraBrand,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.room_loading),
                        fontSize = 14.sp,
                        color = NexoraMuted
                    )
                }
            }
            return
        }

        if (uiState.room == null) {
            // Error State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NexoraSurfaceSecondary)
                        .border(1.dp, NexoraBorder, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.room_error_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = NexoraOnSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.roomError ?: "Oda bulunamadı veya süresi dolmuş olabilir.",
                            fontSize = 13.sp,
                            color = NexoraMuted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexoraBrand,
                                contentColor = NexoraOnBrand
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.back_to_home),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            return
        }

        val room = uiState.room!!

        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            RoomHeader(
                room = room,
                isConnected = uiState.isConnected,
                onBackClick = { viewModel.leaveRoom(onLeft = onNavigateBack) },
                onLeaveClick = { viewModel.leaveRoom(onLeft = onNavigateBack) },
                onNotice = { viewModel.showNotice(it) }
            )

            // Scrollable Room Body
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Video Player
                VideoPlayer(
                    room = room,
                    player = viewModel.player,
                    isHost = uiState.isHost,
                    localVideoName = uiState.localVideoName
                )

                // Video Source Panel
                VideoSourcePanel(
                    isHost = uiState.isHost,
                    currentVideoUrl = room.video?.url ?: "",
                    onSetVideoUrl = { viewModel.setVideoSource(it) },
                    onSelectLocalVideoUri = { viewModel.selectLocalVideo(it) },
                    localVideoName = uiState.localVideoName,
                    onRemoveLocalVideo = { viewModel.removeLocalVideo() },
                    isUploading = uiState.isUploading,
                    uploadProgress = uiState.uploadProgress
                )

                // Participants Panel
                ParticipantsPanel(participants = room.participants)

                // Chat Panel
                ChatPanel(
                    messages = uiState.messages,
                    myParticipantId = uiState.participantId,
                    myNickname = uiState.nickname,
                    onSendMessage = { viewModel.sendMessage(it) }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Floating Notice Toast
        if (uiState.notice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexoraSurfaceSecondary)
                    .border(1.dp, NexoraBrand.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = uiState.notice ?: "",
                    fontSize = 13.sp,
                    color = NexoraOnSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
