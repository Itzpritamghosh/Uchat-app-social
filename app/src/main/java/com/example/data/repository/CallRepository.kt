package com.example.data.repository

import com.example.data.cache.LocalChatCache
import com.example.data.model.AppTimestamp
import com.example.data.model.CallRecord
import com.example.data.model.IceCandidateRecord
import com.example.data.model.UserProfile
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.SupabaseClient.Companion.jsonArrayToStringList
import com.example.data.supabase.SupabaseClient.Companion.listToJsonArray
import com.example.data.supabase.SupabaseClient.Companion.optNullableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CallRepository(
    private val supabase: SupabaseClient,
    private val cache: LocalChatCache? = null
) {
    private fun requireUserId(): String = supabase.requireUserId()

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun observeCalls(userId: String): Flow<List<CallRecord>> =
        supabase.observeTable(
            tables = setOf("calls"),
            fallbackPollMs = 1_500L,
            initialCached = { cache?.getCalls(userId) }
        ) {
            val rows = supabase.restGet(
                table = "calls",
                queryParams = listOf(
                    "participant_uids" to "cs.{\"$userId\"}",
                    "order" to "created_at.desc",
                    "limit" to "50"
                )
            )
            List(rows.length()) { idx -> parseCall(rows.getJSONObject(idx)) }.also { list ->
                cache?.putCalls(userId, list)
            }
        }

    fun observeIceCandidates(callId: String, userId: String): Flow<List<IceCandidateRecord>> =
        supabase.observeTable(setOf("call_ice_candidates"), fallbackPollMs = 1_500L) {
            val rows = supabase.restGet(
                table = "call_ice_candidates",
                queryParams = listOf(
                    "call_id" to "eq.$callId",
                    "participant_uids" to "cs.{\"$userId\"}",
                    "order" to "created_at.asc"
                )
            )
            List(rows.length()) { idx -> parseCandidate(rows.getJSONObject(idx)) }
        }

    suspend fun startCall(
        caller: UserProfile,
        callee: UserProfile,
        callType: String
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val normalizedType = if (callType == "video") "video" else "audio"
        return try {
            val participants = listOf(uid, callee.userId)
            val now = nowIso()
            val payload = JSONObject().apply {
                put("caller_id", uid)
                put("caller_name", caller.displayName.take(60).ifBlank { caller.username })
                put("caller_username", caller.username.take(30))
                put("callee_id", callee.userId)
                put("callee_name", callee.displayName.take(60).ifBlank { callee.username })
                put("callee_username", callee.username.take(30))
                put("participant_uids", listToJsonArray(participants))
                put("call_type", normalizedType)
                put("status", "ringing")
                put("duration_sec", 0L)
                put("updated_at", now)
            }
            val created = supabase.restPost("calls", payload)
            val callId = if (created.length() > 0) created.getJSONObject(0).optString("id", "") else ""
            if (callId.isBlank()) {
                Result.failure(IllegalStateException("Unable to create WebRTC call session."))
            } else {
                Result.success(callId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun publishSdpOffer(callId: String, sdpOffer: String): Result<Unit> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                requireUserId()
                val body = JSONObject().apply {
                    put("sdp_offer", sdpOffer.take(20000))
                    put("updated_at", nowIso())
                }
                supabase.restPatch("calls", listOf("id" to "eq.$callId"), body)
                return Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Failed to publish offer"))
    }

    suspend fun acceptCall(call: CallRecord, sdpAnswer: String): Result<Unit> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                requireUserId()
                val body = JSONObject().apply {
                    put("status", "connecting")
                    put("sdp_answer", sdpAnswer.take(20000))
                    put("updated_at", nowIso())
                }
                supabase.restPatch("calls", listOf("id" to "eq.${call.id}"), body)
                return Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Failed to publish answer"))
    }

    suspend fun markCallConnected(callId: String): Result<Unit> {
        return try {
            requireUserId()
            val body = JSONObject().apply {
                put("status", "connected")
                put("updated_at", nowIso())
            }
            supabase.restPatch("calls", listOf("id" to "eq.$callId"), body)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addIceCandidate(
        call: CallRecord,
        sdp: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val cleanCandidate = sdp.take(2000)
        if (cleanCandidate.isBlank()) return Result.success(Unit)
        return try {
            val payload = JSONObject().apply {
                put("call_id", call.id)
                put("sender_id", uid)
                put("participant_uids", listToJsonArray(call.participantUids.take(2)))
                put("candidate", cleanCandidate)
                put("sdp_mid", sdpMid.take(64))
                put("sdp_mline_index", sdpMLineIndex.coerceIn(0, 32))
            }
            supabase.restPost("call_ice_candidates", payload)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun finishCall(callId: String, terminalStatus: String, durationSec: Long): Result<Unit> {
        val status = when (terminalStatus) {
            "ended", "rejected", "missed", "cancelled" -> terminalStatus
            else -> "ended"
        }
        return try {
            requireUserId()
            val body = JSONObject().apply {
                put("status", status)
                put("duration_sec", durationSec.coerceIn(0L, 86400L))
                put("updated_at", nowIso())
            }
            supabase.restPatch("calls", listOf("id" to "eq.$callId"), body)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCallLogs(calls: List<CallRecord>): Result<Int> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val deletable = calls
            .filter { !it.activeCall() && uid in it.participantUids && it.id.isNotBlank() }
            .distinctBy { it.id }
            .take(50)
        if (deletable.isEmpty()) return Result.success(0)
        return try {
            deletable.forEach { call ->
                supabase.restDelete("calls", listOf("id" to "eq.${call.id}"))
            }
            Result.success(deletable.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearCallHistory(calls: List<CallRecord>): Result<Unit> =
        deleteCallLogs(calls).map { Unit }

    private fun parseCall(obj: JSONObject): CallRecord =
        CallRecord(
            id = obj.optString("id", ""),
            callerId = obj.optString("caller_id", ""),
            callerName = obj.optString("caller_name", ""),
            callerUsername = obj.optString("caller_username", ""),
            calleeId = obj.optString("callee_id", ""),
            calleeName = obj.optString("callee_name", ""),
            calleeUsername = obj.optString("callee_username", ""),
            participantUids = jsonArrayToStringList(obj.optJSONArray("participant_uids")),
            callType = obj.optString("call_type", "audio"),
            status = obj.optString("status", "ringing"),
            sdpOffer = optNullableString(obj, "sdp_offer"),
            sdpAnswer = optNullableString(obj, "sdp_answer"),
            durationSec = obj.optLong("duration_sec", 0L),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )

    private fun parseCandidate(obj: JSONObject): IceCandidateRecord =
        IceCandidateRecord(
            id = obj.optString("id", ""),
            callId = obj.optString("call_id", ""),
            senderId = obj.optString("sender_id", ""),
            participantUids = jsonArrayToStringList(obj.optJSONArray("participant_uids")),
            candidate = obj.optString("candidate", ""),
            sdpMid = obj.optString("sdp_mid", ""),
            sdpMLineIndex = obj.optLong("sdp_mline_index", 0L),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at"))
        )
}
