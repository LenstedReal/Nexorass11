package com.lenstedreal.nexorawatch.session

import android.content.Context
import android.content.SharedPreferences

class SessionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nexora_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NICKNAME_PRIMARY = "nexorawatch:nickname"
        private const val KEY_NICKNAME_LEGACY = "sinerave:nickname"
        private const val PREFIX_ROOM_PRIMARY = "nexorawatch:room:"
        private const val PREFIX_ROOM_LEGACY = "sinerave:room:"
    }

    fun getSavedNickname(): String {
        val primary = prefs.getString(KEY_NICKNAME_PRIMARY, null)
        if (!primary.isNullOrBlank()) return primary
        val legacy = prefs.getString(KEY_NICKNAME_LEGACY, null)
        return legacy ?: ""
    }

    fun saveNickname(nickname: String) {
        val clean = nickname.trim()
        prefs.edit()
            .putString(KEY_NICKNAME_PRIMARY, clean)
            .putString(KEY_NICKNAME_LEGACY, clean)
            .apply()
    }

    fun getRoomSession(code: String): String? {
        val cleanCode = code.trim().uppercase()
        val primary = prefs.getString("$PREFIX_ROOM_PRIMARY$cleanCode", null)
        if (!primary.isNullOrBlank()) return primary
        val legacy = prefs.getString("$PREFIX_ROOM_LEGACY$cleanCode", null)
        return legacy?.takeIf { it.isNotBlank() }
    }

    fun saveRoomSession(code: String, participantId: String) {
        val cleanCode = code.trim().uppercase()
        val cleanId = participantId.trim()
        prefs.edit()
            .putString("$PREFIX_ROOM_PRIMARY$cleanCode", cleanId)
            .putString("$PREFIX_ROOM_LEGACY$cleanCode", cleanId)
            .apply()
    }

    fun clearRoomSession(code: String) {
        val cleanCode = code.trim().uppercase()
        prefs.edit()
            .remove("$PREFIX_ROOM_PRIMARY$cleanCode")
            .remove("$PREFIX_ROOM_LEGACY$cleanCode")
            .apply()
    }
}
