package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TalkRealtimeResponseStateTest {
  @Test
  fun cancelBeforeAcknowledgementTargetsTheCreatedResponseOnce() {
    val state = TalkRealtimeResponseState()
    state.requesting()
    assertNull(state.cancel())
    assertNull(state.cancel())
    assertEquals("pending-response", state.created("pending-response"))
    assertNull(state.cancel())
    assertFalse(state.createInFlight)
    state.completed("pending-response")
    state.requesting()
    assertNull(state.created("next-response"))
  }

  @Test
  fun cancellationTargetsActiveResponseAndLateCompletionCannotClearItsReplacement() {
    val state = TalkRealtimeResponseState()
    assertNull(state.created("first"))
    assertEquals("first", state.cancel())
    assertNull(state.cancel())
    assertNull(state.created("replacement"))
    state.completed("first")
    assertEquals("replacement", state.cancel())
    state.completed("replacement")
    assertNull(state.cancel())
  }
}
