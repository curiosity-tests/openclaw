package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveNativeText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TalkRealtimeClientTest {
  @Test
  fun admissionRetainsOnlyPublishedClientResources() =
    runTest {
      Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
      try {
        for (accepted in listOf(false, true)) {
          val owner = SupervisorJob()
          val clientScope = CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
          val lease = GatewaySession.RequestLease("fixture", requestImpl = { _, _, _, _ -> error("Admission must not allocate a provider session") })
          val client = TalkRealtimeClient(RuntimeEnvironment.getApplication(), clientScope, lease, "main", {}, { _, _, _ -> }, {})
          assertTrue(owner.children.any { it.isActive })
          assertEquals(accepted, client.adopt { accepted })
          runCurrent()
          assertEquals(accepted, owner.children.any { it.isActive })
          assertNull(client.snapshot)
          client.close()
          runCurrent()
          assertFalse(owner.children.any { it.isActive })
          owner.cancel()
        }
      } finally {
        Dispatchers.resetMain()
      }
    }

  @Test
  fun visibleStatusUsesOnlyTheCurrentConfirmedSnapshot() {
    assertEquals(nativeText("Connecting…"), talkRealtimeStatusText("Listening", null))
    val active = TalkRealtimeSnapshot("openai", "gpt-live-1-codex", "oauth", "spruce", "webrtc")
    val nextCall = active.copy(model = "gpt-realtime-2.1", authMethod = "api-key", voice = "alloy")
    val currentText = talkRealtimeStatusText("Thinking", active).resolveNativeText()
    assertTrue(currentText.contains("gpt-live-1-codex / oauth / spruce / webrtc"))
    assertFalse(currentText.contains(nextCall.model!!))
    assertTrue(talkRealtimeStatusText("Listening", nextCall).resolveNativeText().contains("gpt-realtime-2.1 / api-key / alloy / webrtc"))
    assertEquals(nativeText("Connecting…"), talkRealtimeStatusText("Thinking", null))
  }

  @Test
  fun providerVadCompletionArmsCancellationBeforeTheResponseIdArrives() =
    runTest {
      Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
      val lease = GatewaySession.RequestLease("fixture", requestImpl = { _, _, _, _ -> error("No gateway allocation expected") })
      val client = TalkRealtimeClient(RuntimeEnvironment.getApplication(), this, lease, "main", {}, { _, _, _ -> }, {})
      try {
        val event = client.javaClass.getDeclaredMethod("handleProviderEvent", String::class.java).apply { isAccessible = true }
        event.invoke(client, """{"type":"input_audio_buffer.speech_stopped"}""")
        val state =
          client.javaClass
            .getDeclaredField("responseState")
            .apply { isAccessible = true }
            .get(client) as TalkRealtimeResponseState
        assertTrue(state.createInFlight)
        assertNull(state.cancel())
        assertEquals("automatic-response", state.created("automatic-response"))
        assertNull(state.cancel())
      } finally {
        client.close()
        Dispatchers.resetMain()
      }
    }

  @Test
  fun requestsGatewayDefaultsAndRejectsAnotherTransportWithoutNativeFallback() =
    runTest {
      Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
      try {
        val methods = mutableListOf<String>()
        var createParams = ""
        val lease =
          GatewaySession.RequestLease("fixture", requestImpl = { method, params, _, enqueue ->
            enqueue {}
            methods.add(method)
            if (method == "talk.client.create") {
              createParams = checkNotNull(params)
              """{"voiceSessionId":"voice-fixture","transport":"provider-websocket"}"""
            } else {
              "{}"
            }
          })
        val client = TalkRealtimeClient(RuntimeEnvironment.getApplication(), this, lease, "main", {}, { _, _, _ -> }, {})
        val failure = runCatching { client.start() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("unsupported Talk transport"))
        assertEquals(listOf("talk.client.create", "talk.client.close"), methods)
        val params = Json.parseToJsonElement(createParams).jsonObject
        assertEquals("webrtc", params.getValue("transport").jsonPrimitive.content)
        assertFalse(params.containsKey("model"))
        assertFalse(params.containsKey("provider"))
        assertFalse(params.containsKey("authMethod"))
        assertFalse(params.getValue("capabilities").toString().contains("gateway-control-v1"))
      } finally {
        Dispatchers.resetMain()
      }
    }

  @Test
  fun closesAnAllocationReturnedAfterStopWithoutStartingMedia() =
    runTest {
      Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
      try {
        val ack = CompletableDeferred<String>()
        val requested = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        val states = mutableListOf<String>()
        val lease =
          GatewaySession.RequestLease("fixture", requestImpl = { method, _, _, enqueue ->
            enqueue {}
            methods.add(method)
            if (method == "talk.client.create") {
              requested.complete(Unit)
              ack.await()
            } else {
              "{}"
            }
          })
        val client = TalkRealtimeClient(RuntimeEnvironment.getApplication(), this, lease, "main", { states.add(it) }, { _, _, _ -> }, {})
        val starting = async { runCatching { client.start() } }
        requested.await()
        client.close()
        ack.complete("""{"voiceSessionId":"voice-late","transport":"webrtc","clientSecret":"synthetic-capability"}""")
        assertTrue(starting.await().isFailure)
        assertEquals(listOf("talk.client.create", "talk.client.close"), methods)
        assertTrue(states.isEmpty())
        assertNull(client.snapshot)
      } finally {
        Dispatchers.resetMain()
      }
    }
}
