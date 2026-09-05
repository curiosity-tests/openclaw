package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.verbatimText
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TalkRealtimeSnapshot(
  val provider: String,
  val model: String?,
  val authMethod: String?,
  val voice: String?,
  val transport: String,
)

/** One client-owned call, with captured gateway identity and no saved model/auth defaults. */
internal class TalkRealtimeClient(
  context: Context,
  scope: CoroutineScope,
  private val lease: GatewaySession.RequestLease,
  private val sessionKey: String,
  private val onStatus: (String) -> Unit,
  private val onTranscript: (String, String, Boolean) -> Unit,
  private val onFailure: (String) -> Unit,
) {
  // NodeRuntime owns an IO scope; all client response/lifecycle state belongs to Main.
  private val scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
  private val json = Json { ignoreUnknownKeys = true }
  private var closed = false
  private var started = false
  private val responseState = TalkRealtimeResponseState()
  private var voiceSessionId: String? = null
  private var gatewayTranscripts = false
  private var nativeDelegation = false
  private var nativeTranscriptSequence = 0
  private var responsePending = false
  private var transcriptTail: Deferred<Unit> = CompletableDeferred(Unit)
  private var queuedTranscripts = 0
  private val toolBatch = TalkRealtimeToolBatch()
  private val cancelledResponses = mutableSetOf<String>()

  private data class CompletedToolCall(
    val id: String,
    val name: String,
    val args: JsonElement,
  )

  private val completedResponses = mutableSetOf<String>()
  private val finalTranscripts = mutableSetOf<String>()
  private val peer = TalkRealtimePeer(context, scope, ::handleProviderEvent, ::fail)
  private val agent =
    RealtimeAgentCoordinator(
      parentScope = scope,
      requestGateway = { method, params, timeout -> lease.request(method, params, timeout) },
      onWorking = { if (!closed) onStatus("Thinking") },
      onError = { _, _ -> fail("Realtime agent request failed") },
    )
  var snapshot: TalkRealtimeSnapshot? = null
    private set

  /** The caller publishes under its lock; rejected ownership closes outside that lock. */
  suspend fun adopt(publish: () -> Boolean): Boolean {
    val admitted =
      try {
        publish()
      } catch (error: Throwable) {
        close()
        throw error
      }
    if (!admitted) close()
    return admitted
  }

  suspend fun start() =
    withContext(Dispatchers.Main.immediate) {
      check(!closed) { "Realtime call stopped" }
      val params =
        buildJsonObject {
          put("sessionKey", sessionKey)
          put("mode", "realtime")
          put("transport", "webrtc")
          put("brain", "agent-consult")
          put("capabilities", JsonArray(listOf(JsonPrimitive("voice-transcript"))))
        }
      // An accepted create can finish after Stop; retain its bounded ACK so its allocation is closed.
      val payload = withContext(NonCancellable) { lease.request("talk.client.create", params.toString(), 30_000) }
      val result = json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid realtime session")
      voiceSessionId = result.string("voiceSessionId") ?: error("Gateway returned no voice session")
      try {
        check(!closed && lease.isCurrent()) { "Realtime call stopped during setup" }
        check(result.string("transport") == "webrtc") { "Gateway returned an unsupported Talk transport" }
        check(result["clientControl"] == null) { "Client-owned Talk control was not negotiated" }
        gatewayTranscripts = result.string("transcriptOwner") == "gateway"
        nativeDelegation = result.string("controlSource") == "delegation"
        val resolvedSnapshot =
          TalkRealtimeSnapshot(
            provider = result.string("provider") ?: error("Gateway returned no Talk provider"),
            model = result.string("model"),
            authMethod = result.string("authMethod"),
            voice = result.string("voice"),
            transport = "webrtc",
          )
        val secret = result.string("clientSecret") ?: error("Gateway returned no offer capability")
        val offerUrl = result.string("offerUrl") ?: error("Gateway returned no offer URL")
        val headers =
          (result["offerHeaders"] as? JsonObject)
            ?.mapValues { (_, value) ->
              (value as? JsonPrimitive)?.content ?: error("Invalid offer header")
            }.orEmpty()
        val route = lease.realtimeOfferRoute(offerUrl)
        val voiceId = checkNotNull(voiceSessionId)
        agent.beginSession(
          RealtimeAgentSession(
            voiceId,
            sessionKey,
            RealtimeAgentClientTransport(
              request = { method, args, timeout ->
                if (method == "talk.client.toolCall") transcriptTail.await()
                lease.request(method, args, timeout)
              },
              submit = ::submitToolResult,
            ),
          ),
        )
        peer.start { offer -> route.exchange(secret, headers, offer) }
        if (!closed) {
          started = true
          snapshot = resolvedSnapshot
          onStatus("Listening")
        }
      } catch (error: Throwable) {
        withContext(NonCancellable) { close() }
        throw error
      }
    }

  fun handleGatewayEvent(
    event: String,
    payload: String?,
  ): Boolean {
    if (event != "chat" || payload == null) return false
    val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return false
    return agent.handleChatEvent(obj.string("sessionKey"), obj.string("runId") ?: return false, obj.string("state") ?: return false, obj["message"])
  }

  private fun handleProviderEvent(payload: String) {
    if (closed) return
    val event = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return fail("Invalid realtime event")
    if (event.string("response_id") in cancelledResponses && event.string("type")?.contains("transcript") == true) return
    val itemId = event.string("item_id")
    when (event.string("type")) {
      "conversation.item.input_audio_transcription.completed" -> {
        transcript("user", event.string("transcript"), itemId, true)
      }

      "response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.output_text.delta" -> {
        transcript("assistant", event.string("delta"), itemId, false)
      }

      "response.output_audio_transcript.done", "response.audio_transcript.done", "response.output_text.done" -> {
        transcript("assistant", event.string("transcript") ?: event.string("text"), itemId, true)
      }

      "input_transcript.added", "output_transcript.added" -> {
        val item = event["item"] as? JsonObject
        transcript(if (event.string("type") == "input_transcript.added") "user" else "assistant", item?.string("text"), item?.string("id"), false)
      }

      "turn.done" -> {
        val turn = event["turn"] as? JsonObject ?: return
        // Frameless Bidi does not require a turn id. The reliable data channel owns
        // delivery order; retain one local id for each queued persistence operation.
        val entryId = turn.string("id") ?: "native-${++nativeTranscriptSequence}"
        transcript(turn.string("role") ?: return, turn.string("transcript"), entryId, true)
      }

      "input_audio_buffer.speech_started" -> {
        onStatus("Listening")
      }

      "input_audio_buffer.speech_stopped" -> {
        // The Gateway GA policy enables VAD-created responses; they have the same
        // pre-acknowledgement cancellation window as an explicit response.create.
        responseState.requesting()
        onStatus("Thinking")
      }

      "output_audio_buffer.started" -> {
        onStatus("Speaking")
      }

      "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
        onStatus("Listening")
      }

      "response.created" -> {
        val id = (event["response"] as? JsonObject)?.string("id") ?: return fail("Missing realtime response id")
        if (id in completedResponses) return
        val cancelled = responseState.created(id)
        if (cancelled != null) {
          remember(cancelledResponses, cancelled)
          scope.launch { cancelResponse(cancelled) }
        } else {
          onStatus("Thinking")
        }
      }

      "response.done" -> {
        val response = event["response"] as? JsonObject ?: return fail("Invalid realtime response")
        val id = response.string("id") ?: return fail("Missing realtime response id")
        if (!remember(completedResponses, id)) return
        responseState.completed(id)
        when {
          response.string("status") == "completed" && id !in cancelledResponses -> {
            val calls = mutableListOf<CompletedToolCall>()
            for (value in response["output"] as? JsonArray ?: JsonArray(emptyList())) {
              val item = value as? JsonObject ?: continue
              if (item.string("type") != "function_call" || item.string("status")?.let { it != "completed" } == true) continue
              val callId = item.string("call_id") ?: continue
              val name = item.string("name") ?: continue
              val arguments = item.string("arguments") ?: continue
              if (arguments.toByteArray().size > 256_000) return fail("Realtime tool arguments exceed limit")
              val args = runCatching { json.parseToJsonElement(arguments) }.getOrNull() ?: return fail("Invalid realtime tool arguments")
              calls.add(CompletedToolCall(callId, name, args))
            }
            val admitted = runCatching { toolBatch.admit(calls.map { it.id }).toMutableSet() }.getOrElse { return fail("Realtime tool-call limit exceeded") }
            for (call in calls) {
              if (admitted.remove(call.id)) agent.handleToolCall(call.id, call.name, call.args, false)
            }
          }

          response.string("status") == "cancelled" || id in cancelledResponses -> {}

          else -> {
            fail("Realtime response failed or incomplete")
          }
        }
        if (responsePending && !closed && responseState.responseId == null && !toolBatch.hasPending) {
          responsePending = false
          scope.launch { sendResponse() }
        }
      }

      "error", "conversation.item.input_audio_transcription.failed" -> {
        fail("Realtime provider reported an error")
      }
    }
  }

  private fun remember(
    ids: MutableSet<String>,
    id: String,
  ): Boolean {
    if (id in ids) return false
    if (ids.size >= 1024) {
      fail("Realtime call event limit exceeded")
      return false
    }
    return ids.add(id)
  }

  private fun transcript(
    role: String,
    text: String?,
    itemId: String?,
    final: Boolean,
  ) {
    if (role !in listOf("user", "assistant") || text.isNullOrEmpty()) return
    if (!final && itemId != null && "$role:$itemId" in finalTranscripts) return
    if (final) {
      if (itemId == null) return fail("Realtime transcript has no item identity")
      if (!remember(finalTranscripts, "$role:$itemId")) return
      if (!gatewayTranscripts) {
        val voiceId = voiceSessionId ?: return
        if (queuedTranscripts >= 128) return fail("Realtime transcript queue overflow")
        queuedTranscripts++
        val previous = transcriptTail
        transcriptTail =
          scope.async(Dispatchers.Main.immediate) {
            try {
              previous.await()
              lease.request(
                "talk.client.transcript",
                buildJsonObject {
                  put("sessionKey", sessionKey)
                  put("voiceSessionId", voiceId)
                  put("entryId", "$role:$itemId")
                  put("role", role)
                  put("text", text)
                }.toString(),
                10_000,
              )
            } catch (error: Exception) {
              fail("Voice transcript could not be saved")
              throw error
            } finally {
              queuedTranscripts--
            }
          }
      }
    }
    onTranscript(role, text, final)
  }

  private suspend fun submitToolResult(
    callId: String,
    result: JsonObject,
  ) = withContext(Dispatchers.Main.immediate) {
    if (closed) return@withContext
    val batchComplete = toolBatch.complete(callId) ?: return@withContext
    peer.send(
      buildJsonObject {
        put("type", "conversation.item.create")
        put(
          "item",
          buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", result.toString())
          },
        )
      }.toString(),
    )
    if (!batchComplete) return@withContext
    if (responseState.responseId != null || responseState.createInFlight) responsePending = true else sendResponse()
  }

  private suspend fun sendResponse() {
    if (!closed) {
      responseState.requesting()
      peer.send("{\"type\":\"response.create\"}")
    }
  }

  private suspend fun cancelResponse(id: String) {
    if (closed) return
    remember(cancelledResponses, id)
    try {
      peer.send(
        buildJsonObject {
          put("type", "response.cancel")
          put("response_id", id)
        }.toString(),
      )
      peer.send("{\"type\":\"output_audio_buffer.clear\"}")
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (_: Exception) {
      fail("Realtime response cancellation could not be sent")
    }
  }

  suspend fun setCaptureEnabled(enabled: Boolean) = peer.setCaptureEnabled(enabled)

  suspend fun setPlaybackEnabled(enabled: Boolean) = peer.setPlaybackEnabled(enabled)

  suspend fun cancelOutput() =
    withContext(Dispatchers.Main.immediate) {
      if (closed || !started) return@withContext
      if (nativeDelegation) {
        // Frameless Bidi has no client cancel primitive; closing is explicit,
        // unlike sending a GA event that the native protocol cannot honor.
        fail("Native realtime response cancellation ended the call")
        return@withContext
      }
      val id = responseState.cancel()
      responsePending = false
      if (id != null) cancelResponse(id)
      if (id == null && responseState.responseId == null && !responseState.createInFlight && !gatewayTranscripts) peer.send("{\"type\":\"output_audio_buffer.clear\"}")
    }

  suspend fun close() =
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      closed = true
      snapshot = null
      agent.endSession()
      peer.close()
      val voiceId = voiceSessionId ?: return@withContext
      voiceSessionId = null
      try {
        transcriptTail.await()
      } finally {
        runCatching {
          lease.request(
            "talk.client.close",
            buildJsonObject {
              put("sessionKey", sessionKey)
              put("voiceSessionId", voiceId)
            }.toString(),
            5_000,
          )
        }.onFailure { onFailure("Realtime session close could not be confirmed") }
      }
    }

  private fun fail(message: String) {
    if (closed) return
    closed = true
    onFailure(message)
    scope.launch { close() }
  }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Only the committed call supplies identity; connecting never reuses an earlier call's values. */
internal fun talkRealtimeStatusText(
  state: String,
  snapshot: TalkRealtimeSnapshot?,
): NativeText {
  if (snapshot == null) return nativeText("Connecting…")
  val details = listOf(snapshot.provider, snapshot.model ?: "Unknown", snapshot.authMethod ?: "Unknown", snapshot.voice ?: "Unknown", snapshot.transport).joinToString(" / ")
  return nativeText("Talk: \$state — \$details", state, verbatimText(details))
}
