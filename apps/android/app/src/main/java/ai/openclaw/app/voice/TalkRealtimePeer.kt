package ai.openclaw.app.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/** One WebRTC call. All JNI ownership is serialized on Main, never on a native callback thread. */
internal class TalkRealtimePeer(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onEvent: (String) -> Unit,
  private val onFailure: (String) -> Unit,
) {
  private var factory: PeerConnectionFactory? = null
  private var audioDevice: JavaAudioDeviceModule? = null
  private var source: AudioSource? = null
  private var track: AudioTrack? = null
  private var peer: PeerConnection? = null
  private var channel: DataChannel? = null
  private var closed = false
  private var captureEnabled = true
  private var playbackEnabled = true
  private val ready = CompletableDeferred<Unit>()
  private val events = Channel<String>(64)
  private val eventPump: Job =
    scope.launch(Dispatchers.Main.immediate) {
      try {
        for (event in events) {
          if (!closed) onEvent(event)
        }
      } catch (error: Exception) {
        if (error !is CancellationException) onFailure("Realtime event processing failed")
      } finally {
        // Parent cancellation must release JNI capture, not only stop event delivery.
        close()
      }
    }

  suspend fun start(exchangeOffer: suspend (String) -> String) =
    withContext(Dispatchers.Main.immediate) {
      check(!closed && peer == null) { "Realtime peer is not available" }
      try {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        audioDevice = audio
        val createdFactory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
        factory = createdFactory
        val config =
          PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
          }
        val createdPeer = checkNotNull(createdFactory.createPeerConnection(config, observer)) { "Could not create realtime peer" }
        peer = createdPeer
        createdPeer.setAudioRecording(captureEnabled)
        createdPeer.setAudioPlayout(playbackEnabled)
        val createdSource = createdFactory.createAudioSource(MediaConstraints())
        source = createdSource
        val createdTrack = createdFactory.createAudioTrack("openclaw-talk-audio", createdSource)
        track = createdTrack
        createdTrack.setEnabled(captureEnabled)
        createdPeer.addTrack(createdTrack, listOf("openclaw-talk"))
        val createdChannel = checkNotNull(createdPeer.createDataChannel("oai-events", DataChannel.Init()))
        channel = createdChannel
        createdChannel.registerObserver(
          object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
              scope.launch(Dispatchers.Main.immediate) {
                if (closed || channel !== createdChannel) return@launch
                when (createdChannel.state()) {
                  DataChannel.State.OPEN -> ready.complete(Unit)
                  DataChannel.State.CLOSED -> fail("Realtime data channel closed")
                  else -> Unit
                }
              }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
              // WebRTC frees the native buffer after this callback returns (DataChannel.java).
              if (buffer.binary || buffer.data.remaining() > 1_048_576) {
                scope.launch(Dispatchers.Main.immediate) { fail("Invalid realtime event") }
                return
              }
              val bytes = ByteArray(buffer.data.remaining())
              buffer.data.get(bytes)
              if (events.trySend(bytes.toString(Charsets.UTF_8)).isFailure) {
                scope.launch(Dispatchers.Main.immediate) { fail("Realtime event queue overflow") }
              }
            }
          },
        )
        withTimeout(30_000) {
          val offer = CompletableDeferred<SessionDescription>()
          createdPeer.createOffer(SdpResult(offer), MediaConstraints())
          val local = offer.await()
          checkCurrent(createdPeer)
          val localSet = CompletableDeferred<Unit>()
          createdPeer.setLocalDescription(SdpResult(set = localSet), local)
          localSet.await()
          checkCurrent(createdPeer)
          val answer = exchangeOffer(local.description)
          checkCurrent(createdPeer)
          val remoteSet = CompletableDeferred<Unit>()
          createdPeer.setRemoteDescription(SdpResult(set = remoteSet), SessionDescription(SessionDescription.Type.ANSWER, answer))
          remoteSet.await()
          checkCurrent(createdPeer)
          ready.await()
          checkCurrent(createdPeer)
        }
      } catch (error: Throwable) {
        close()
        throw error
      }
    }

  suspend fun send(event: String) =
    withContext(Dispatchers.Main.immediate) {
      val current = channel
      check(!closed && current?.state() == DataChannel.State.OPEN) { "Realtime data channel is not open" }
      check(current.send(DataChannel.Buffer(ByteBuffer.wrap(event.toByteArray(Charsets.UTF_8)), false))) { "Realtime event was not sent" }
    }

  suspend fun setCaptureEnabled(enabled: Boolean) =
    withContext(Dispatchers.Main.immediate) {
      if (closed) return@withContext
      captureEnabled = enabled
      // Muting samples alone leaves AudioRecord alive and races PTT microphone ownership.
      track?.setEnabled(enabled)
      peer?.setAudioRecording(enabled)
    }

  suspend fun setPlaybackEnabled(enabled: Boolean) =
    withContext(Dispatchers.Main.immediate) {
      playbackEnabled = enabled
      if (!closed) peer?.setAudioPlayout(enabled)
    }

  suspend fun close(): Unit =
    withContext(NonCancellable + Dispatchers.Main.immediate) {
      if (closed) return@withContext
      closed = true
      ready.cancel()
      events.close()
      eventPump.cancel()
      channel?.let {
        it.unregisterObserver()
        it.close()
        it.dispose()
      }
      channel = null
      peer?.dispose()
      peer = null
      track?.dispose()
      track = null
      source?.dispose()
      source = null
      factory?.dispose()
      factory = null
      audioDevice?.release()
      audioDevice = null
    }

  private fun checkCurrent(expected: PeerConnection) {
    check(!closed && peer === expected) { "Realtime call stopped during setup" }
  }

  private fun fail(message: String) {
    if (closed) return
    ready.completeExceptionally(IllegalStateException(message))
    onFailure(message)
  }

  private val observer =
    object : PeerConnection.Observer {
      override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

      override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

      override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

      override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

      override fun onIceCandidate(candidate: IceCandidate) = Unit

      override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

      override fun onAddStream(stream: MediaStream) = Unit

      override fun onRemoveStream(stream: MediaStream) = Unit

      override fun onDataChannel(channel: DataChannel) = Unit

      override fun onRenegotiationNeeded() = Unit

      override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
          scope.launch(Dispatchers.Main.immediate) { fail("Realtime connection closed") }
        }
      }
    }

  private class SdpResult(
    private val offer: CompletableDeferred<SessionDescription>? = null,
    private val set: CompletableDeferred<Unit>? = null,
  ) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {
      offer?.complete(description)
    }

    override fun onSetSuccess() {
      set?.complete(Unit)
    }

    override fun onCreateFailure(error: String) {
      offer?.completeExceptionally(IllegalStateException("Realtime SDP creation failed"))
    }

    override fun onSetFailure(error: String) {
      set?.completeExceptionally(IllegalStateException("Realtime SDP setup failed"))
    }
  }
}
