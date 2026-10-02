package com.lenstedreal.nexorawatch.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NexoraException(val statusCode: Int, message: String) : Exception(message)

class NexoraRepository(
    baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://nexorawatch-beta.vercel.app/"
        const val DEFAULT_WS_URL = "wss://nexorawatch-beta.vercel.app/api/ws"
    }

    private val gson = Gson()

    val okHttpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api: NexoraApi = retrofit.create(NexoraApi::class.java)

    private fun <T> handleResponse(response: Response<T>): T {
        if (response.isSuccessful && response.body() != null) {
            return response.body()!!
        }

        var errorDetail = "İstek başarısız oldu (${response.code()})"
        try {
            val errorBody = response.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                val json = gson.fromJson(errorBody, JsonObject::class.java)
                if (json.has("detail") && !json.get("detail").isJsonNull) {
                    errorDetail = json.get("detail").asString
                } else if (json.has("error") && !json.get("error").isJsonNull) {
                    errorDetail = json.get("error").asString
                }
            }
        } catch (_: Exception) {}

        throw NexoraException(response.code(), errorDetail)
    }

    suspend fun createRoom(nickname: String, name: String): JoinResponse = withContext(Dispatchers.IO) {
        val nick = nickname.trim()
        val roomName = name.trim().ifEmpty { "${nick}'in odası" }
        val response = api.createRoom(CreateRoomRequest(nick, roomName))
        handleResponse(response)
    }

    suspend fun joinRoom(code: String, nickname: String, participantId: String? = null): JoinResponse = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val nick = nickname.trim()
        val response = api.joinRoom(cleanCode, JoinRoomRequest(nick, participantId))
        handleResponse(response)
    }

    suspend fun getRoom(code: String): Room = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.getRoom(cleanCode)
        handleResponse(response)
    }

    suspend fun getMessages(code: String): List<Message> = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.getMessages(cleanCode)
        handleResponse(response)
    }

    suspend fun sendMessage(code: String, participantId: String, text: String): Message = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.sendMessage(cleanCode, SendMessageRequest(participantId, text.trim()))
        handleResponse(response)
    }

    suspend fun setVideo(code: String, participantId: String, url: String): Room = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.setVideo(cleanCode, SetVideoRequest(participantId, url.trim()))
        handleResponse(response)
    }

    suspend fun setPlayback(code: String, participantId: String, playing: Boolean, position: Double): Playback = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.setPlayback(cleanCode, SetPlaybackRequest(participantId, playing, position))
        handleResponse(response)
    }

    suspend fun setWeb(code: String, participantId: String, open: Boolean, url: String?): Room = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val response = api.setWeb(cleanCode, SetWebRequest(participantId, open, url))
        handleResponse(response)
    }

    suspend fun leaveRoom(code: String, participantId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanCode = code.trim().uppercase()
            val response = api.leaveRoom(cleanCode, LeaveRoomRequest(participantId))
            handleResponse(response).ok
        } catch (_: Exception) {
            false
        }
    }

    suspend fun uploadVideo(
        code: String,
        participantId: String,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): UploadResponse = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val codePart = cleanCode.toRequestBody("text/plain".toMediaTypeOrNull())
        val participantPart = participantId.toRequestBody("text/plain".toMediaTypeOrNull())
        val fileReqBody = fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", fileName, fileReqBody)

        val response = api.uploadVideo(codePart, participantPart, filePart)
        handleResponse(response)
    }
}
