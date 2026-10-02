package com.lenstedreal.nexorawatch.ui.room

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lenstedreal.nexorawatch.data.Message
import com.lenstedreal.nexorawatch.data.NexoraRepository
import com.lenstedreal.nexorawatch.data.Participant
import com.lenstedreal.nexorawatch.data.Room
import com.lenstedreal.nexorawatch.player.NexoraPlayer
import com.lenstedreal.nexorawatch.player.PlaybackSync
import com.lenstedreal.nexorawatch.realtime.NexoraWebSocket
import com.lenstedreal.nexorawatch.realtime.RealtimeEvent
import com.lenstedreal.nexorawatch.session.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream

data class RoomUiState(
    val room: Room? = null,
    val roomLoading: Boolean = true,
    val roomError: String? = null,
    val messages: List<Message> = emptyList(),
    val isConnected: Boolean = false,
    val isHost: Boolean = false,
    val participantId: String? = null,
    val nickname: String = "",
    val notice: String? = null,
    val isUploading: Boolean = false,
    val uploadProgress: Int = 0,
    val localVideoName: String? = null
)

class RoomViewModel(
    val code: String,
    private val repository: NexoraRepository,
    private val sessionStore: SessionStore,
    private val webSocket: NexoraWebSocket,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    var player: NexoraPlayer? = null
        private set

    private var syncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isSyncingRemote = false

    init {
        player = NexoraPlayer(context)

        val savedNick = sessionStore.getSavedNickname()
        val participantId = sessionStore.getRoomSession(code)

        _uiState.value = _uiState.value.copy(
            nickname = savedNick,
            participantId = participantId
        )

        loadInitialData(participantId)
        observeWebSocket()
        startSyncLoop()
    }

    private fun loadInitialData(participantId: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(roomLoading = true, roomError = null)
            try {
                val room = repository.getRoom(code)
                val messages = try { repository.getMessages(code) } catch (_: Exception) { emptyList() }

                val me = room.participants.find { it.id == participantId }
                val isHost = me?.isHost == true

                _uiState.value = _uiState.value.copy(
                    room = room,
                    messages = messages,
                    roomLoading = false,
                    isHost = isHost
                )

                if (participantId != null) {
                    webSocket.connect(code, participantId)
                }

                // If room already has a video, set player source
                room.video?.let { v ->
                    val streamUrl = v.streamUrl ?: v.embedUrl ?: v.url
                    player?.setSource(streamUrl)

                    // If host resumes, seek to target
                    val targetPos = PlaybackSync.calculateExpectedPosition(room.playback, webSocket.serverOffset.value)
                    player?.seekTo(targetPos)
                    if (room.playback.playing) player?.play() else player?.pause()
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    roomLoading = false,
                    roomError = e.message ?: "Oda bulunamadı veya süresi dolmuş olabilir."
                )
            }
        }
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            webSocket.isConnected.collect { connected ->
                _uiState.value = _uiState.value.copy(isConnected = connected)
            }
        }

        viewModelScope.launch {
            webSocket.events.collect { event ->
                when (event) {
                    is RealtimeEvent.RoomUpdated -> {
                        val currentRoom = _uiState.value.room
                        val updatedRoom = event.room
                        _uiState.value = _uiState.value.copy(
                            room = updatedRoom,
                            isHost = updatedRoom.participants.find { it.id == _uiState.value.participantId }?.isHost == true
                        )

                        // If video source changed
                        if (currentRoom?.video?.url != updatedRoom.video?.url) {
                            updatedRoom.video?.let { v ->
                                val streamUrl = v.streamUrl ?: v.embedUrl ?: v.url
                                player?.setSource(streamUrl)
                            }
                        }
                    }
                    is RealtimeEvent.PlaybackUpdated -> {
                        val currentRoom = _uiState.value.room
                        if (currentRoom != null) {
                            val updatedPlayback = event.playback
                            _uiState.value = _uiState.value.copy(
                                room = currentRoom.copy(playback = updatedPlayback)
                            )
                        }
                    }
                    is RealtimeEvent.MessageReceived -> {
                        val currentMessages = _uiState.value.messages.toMutableList()
                        val newMsg = event.message
                        if (currentMessages.none { it.key == newMsg.key }) {
                            currentMessages.add(newMsg)
                            _uiState.value = _uiState.value.copy(messages = currentMessages)
                        }
                    }
                    is RealtimeEvent.PresenceUpdated -> {
                        val currentRoom = _uiState.value.room
                        if (currentRoom != null) {
                            val updatedParticipants = currentRoom.participants.map { p ->
                                if (p.id == event.participantId) p.copy(online = event.online) else p
                            }
                            _uiState.value = _uiState.value.copy(
                                room = currentRoom.copy(participants = updatedParticipants)
                            )
                        }
                    }
                    is RealtimeEvent.WebUpdated -> {
                        val currentRoom = _uiState.value.room
                        if (currentRoom != null) {
                            _uiState.value = _uiState.value.copy(
                                room = currentRoom.copy(webOpen = event.open, webUrl = event.url)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val state = _uiState.value
                val room = state.room ?: continue
                val p = player ?: continue

                if (state.isHost) {
                    // Host Heartbeat
                    if (p.isPlaying) {
                        try {
                            repository.setPlayback(
                                code = code,
                                participantId = state.participantId ?: "",
                                playing = true,
                                position = p.currentPositionSeconds
                            )
                        } catch (_: Exception) {}
                    }
                } else {
                    // Guest Synchronization
                    if (state.localVideoName == null && room.video != null) {
                        val targetPos = PlaybackSync.calculateExpectedPosition(room.playback, webSocket.serverOffset.value)
                        val currentPos = p.currentPositionSeconds

                        if (PlaybackSync.isDriftExceeded(currentPos, targetPos)) {
                            p.seekTo(targetPos)
                        }

                        if (room.playback.playing && !p.isPlaying) {
                            p.play()
                        } else if (!room.playback.playing && p.isPlaying) {
                            p.pause()
                        }
                    }
                }
            }
        }
    }

    fun showNotice(text: String) {
        _uiState.value = _uiState.value.copy(notice = text)
        viewModelScope.launch {
            delay(2600)
            if (_uiState.value.notice == text) {
                _uiState.value = _uiState.value.copy(notice = null)
            }
        }
    }

    fun setVideoSource(url: String) {
        val participantId = _uiState.value.participantId ?: return
        viewModelScope.launch {
            try {
                val updatedRoom = repository.setVideo(code, participantId, url.trim())
                _uiState.value = _uiState.value.copy(room = updatedRoom)
                updatedRoom.video?.let { v ->
                    val streamUrl = v.streamUrl ?: v.embedUrl ?: v.url
                    player?.setSource(streamUrl)
                }
                showNotice("Video kaynağı güncellendi.")
            } catch (e: Exception) {
                showNotice("Bu bağlantı desteklenen bir video kaynağı olarak çözülemedi.")
            }
        }
    }

    fun selectLocalVideo(uri: Uri) {
        val participantId = _uiState.value.participantId ?: return
        if (!_uiState.value.isHost) {
            showNotice("Yerel videoyu yalnızca oda sahibi yükleyebilir.")
            return
        }

        viewModelScope.launch {
            try {
                // Verify duration <= 30 seconds
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationMsStr?.toLongOrNull() ?: 0L
                retriever.release()

                if (durationMs <= 0) {
                    showNotice("Video süresi okunamadı.")
                    return@launch
                }

                if (durationMs > 30_500L) {
                    showNotice("Cihazdan seçilen video 30 saniye veya daha kısa olmalıdır.")
                    return@launch
                }

                val fileName = "local_video_${System.currentTimeMillis()}.mp4"
                _uiState.value = _uiState.value.copy(
                    localVideoName = fileName,
                    isUploading = true,
                    uploadProgress = 10
                )

                // Play locally in player immediately
                player?.setSource(uri.toString())
                player?.play()

                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: byteArrayOf()
                inputStream?.close()

                if (bytes.size > 2 * 1024 * 1024 * 1024L) {
                    showNotice("Video en fazla 2 GB olabilir.")
                    _uiState.value = _uiState.value.copy(isUploading = false)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(uploadProgress = 50)
                val uploadResult = repository.uploadVideo(
                    code = code,
                    participantId = participantId,
                    fileBytes = bytes,
                    fileName = fileName,
                    mimeType = "video/mp4"
                )

                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadProgress = 100,
                    room = uploadResult.room ?: _uiState.value.room
                )

                showNotice("Yerel video odaya yüklendi ve senkronize edildi.")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isUploading = false)
                showNotice(e.message ?: "Video yüklenemedi.")
            }
        }
    }

    fun removeLocalVideo() {
        _uiState.value = _uiState.value.copy(localVideoName = null)
        _uiState.value.room?.video?.let { v ->
            val streamUrl = v.streamUrl ?: v.embedUrl ?: v.url
            player?.setSource(streamUrl)
        }
    }

    fun sendMessage(text: String) {
        val participantId = _uiState.value.participantId ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return

        viewModelScope.launch {
            try {
                webSocket.sendChatMessage(clean)
                repository.sendMessage(code, participantId, clean)
            } catch (e: Exception) {
                showNotice(e.message ?: "Mesaj gönderilemedi.")
            }
        }
    }

    fun leaveRoom(onLeft: () -> Unit) {
        val participantId = _uiState.value.participantId
        viewModelScope.launch {
            if (participantId != null) {
                try {
                    repository.leaveRoom(code, participantId)
                } catch (_: Exception) {}
                sessionStore.clearRoomSession(code)
            }
            webSocket.disconnect()
            player?.pause()
            player?.release()
            player = null
            onLeft()
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
        heartbeatJob?.cancel()
        webSocket.disconnect()
        player?.release()
        player = null
    }
}
