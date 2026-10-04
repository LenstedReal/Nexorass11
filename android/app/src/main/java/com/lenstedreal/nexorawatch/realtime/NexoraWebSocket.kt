package com.lenstedreal.nexorawatch.realtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lenstedreal.nexorawatch.data.Message
import com.lenstedreal.nexorawatch.data.Playback
import com.lenstedreal.nexorawatch.data.Room
import com.lenstedreal.nexorawatch.player.PlaybackSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder

enum class ConnectionStatus {
    CONNECTED,      // "Bağlı"
    RECONNECTING,   // "Yeniden bağlanıyor"
    DISCONNECTED    // "Bağlantı yok"
}

sealed class RealtimeEvent {
    data class RoomUpdated(val room: Room) : RealtimeEvent()
    data class PlaybackUpdated(val playback: Playback, val serverTime: Long?) : RealtimeEvent()
    data class MessageReceived(val message: Message) : RealtimeEvent()
    data class PresenceUpdated(val participantId: String, val online: Boolean) : RealtimeEvent()
    data class WebUpdated(val open: Boolean, val url: String?) : RealtimeEvent()
    data class ReactionReceived(val emoji: String, val senderNickname: String) : RealtimeEvent()
}

class NexoraWebSocket(
    private val client: OkHttpClient,
    private val wsEndpoint: String = "wss://nexorawatch-beta.vercel.app/api/ws"
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var isClosedIntentionally = false
    private var hasInitialOffset = false

    private var currentCode: String? = null
    private var currentParticipantId: String? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _serverOffset = MutableStateFlow(0L)
    val serverOffset: StateFlow<Long> = _serverOffset.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    fun connect(code: String, participantId: String) {
        isClosedIntentionally = false
        currentCode = code.trim().uppercase()
        currentParticipantId = participantId.trim()
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        initiateConnection()
    }

    private fun initiateConnection() {
        val code = currentCode ?: return
        val participantId = currentParticipantId ?: return

        reconnectJob?.cancel()
        webSocket?.cancel()

        val encodedCode = URLEncoder.encode(code, "UTF-8")
        val encodedParticipant = URLEncoder.encode(participantId, "UTF-8")
        val url = "$wsEndpoint?code=$encodedCode&participantId=$encodedParticipant"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                if (!isClosedIntentionally) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                stopPingLoop()
                if (!isClosedIntentionally) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                stopPingLoop()
                if (!isClosedIntentionally) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }
        })
    }

    fun updateServerTime(serverTime: Long?) {
        if (serverTime == null || serverTime <= 0L) return
        val nextOffset = serverTime - System.currentTimeMillis()
        if (PlaybackSync.shouldUpdateServerOffset(_serverOffset.value, nextOffset, hasInitialOffset)) {
            _serverOffset.value = nextOffset
            hasInitialOffset = true
        }
    }

    fun reportHttpPollResult(success: Boolean, hasNetwork: Boolean, serverTime: Long? = null) {
        if (serverTime != null) {
            updateServerTime(serverTime)
        }
        if (!hasNetwork) {
            _isConnected.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }
        if (success) {
            _connectionStatus.value = ConnectionStatus.CONNECTED
        } else if (!_isConnected.value) {
            _connectionStatus.value = ConnectionStatus.RECONNECTING
        }
    }

    private fun handleMessage(jsonString: String) {
        try {
            val json = gson.fromJson(jsonString, JsonObject::class.java) ?: return
            val type = if (json.has("type")) json.get("type").asString else return

            if (json.has("server_time") && !json.get("server_time").isJsonNull) {
                updateServerTime(json.get("server_time").asLong)
            }

            when (type) {
                "room" -> {
                    if (json.has("room")) {
                        val room = gson.fromJson(json.get("room"), Room::class.java)
                        updateServerTime(room.serverTime)
                        _events.tryEmit(RealtimeEvent.RoomUpdated(room))
                    }
                }
                "playback" -> {
                    if (json.has("playback")) {
                        val playback = gson.fromJson(json.get("playback"), Playback::class.java)
                        val serverTime = if (json.has("server_time") && !json.get("server_time").isJsonNull) {
                            json.get("server_time").asLong
                        } else null
                        _events.tryEmit(RealtimeEvent.PlaybackUpdated(playback, serverTime))
                    }
                }
                "message" -> {
                    if (json.has("message")) {
                        val msg = gson.fromJson(json.get("message"), Message::class.java)
                        _events.tryEmit(RealtimeEvent.MessageReceived(msg))
                    }
                }
                "presence" -> {
                    val pId = if (json.has("participant_id")) json.get("participant_id").asString else ""
                    val online = if (json.has("online")) json.get("online").asBoolean else false
                    if (pId.isNotEmpty()) {
                        _events.tryEmit(RealtimeEvent.PresenceUpdated(pId, online))
                    }
                }
                "web" -> {
                    val open = if (json.has("open")) json.get("open").asBoolean else false
                    val url = if (json.has("url") && !json.get("url").isJsonNull) json.get("url").asString else null
                    _events.tryEmit(RealtimeEvent.WebUpdated(open, url))
                }
                "reaction" -> {
                    val emoji = if (json.has("emoji")) json.get("emoji").asString else ""
                    val sender = if (json.has("nickname")) json.get("nickname").asString else ""
                    if (emoji.isNotEmpty()) {
                        _events.tryEmit(RealtimeEvent.ReactionReceived(emoji, sender))
                    }
                }
                "pong" -> {
                    // Server offset updated above
                }
            }
        } catch (_: Exception) {}
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(20_000)
                sendRaw(mapOf("type" to "ping"))
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2500)
            if (!isClosedIntentionally) {
                initiateConnection()
            }
        }
    }

    fun sendPlayback(playing: Boolean, position: Double) {
        sendRaw(mapOf(
            "type" to "playback",
            "playing" to playing,
            "position" to position
        ))
    }

    fun sendChatMessage(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty() && clean.length <= 1000) {
            sendRaw(mapOf(
                "type" to "message",
                "text" to clean
            ))
        }
    }

    fun sendReaction(emoji: String, nickname: String) {
        sendRaw(mapOf(
            "type" to "reaction",
            "emoji" to emoji,
            "nickname" to nickname
        ))
    }

    fun sendWeb(open: Boolean, url: String?) {
        val payload = mutableMapOf<String, Any>(
            "type" to "web",
            "open" to open
        )
        if (url != null) payload["url"] = url
        sendRaw(payload)
    }

    private fun sendRaw(data: Any): Boolean {
        return try {
            val json = gson.toJson(data)
            webSocket?.send(json) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        isClosedIntentionally = true
        stopPingLoop()
        reconnectJob?.cancel()
        webSocket?.close(1000, "User left")
        webSocket = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}
