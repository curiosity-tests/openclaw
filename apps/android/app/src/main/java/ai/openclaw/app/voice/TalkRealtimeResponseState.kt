package ai.openclaw.app.voice

/** Owns the unacknowledged response-create window as well as the active response. */
internal class TalkRealtimeResponseState {
  var responseId: String? = null
    private set
  var createInFlight = false
    private set
  private var cancelOnCreation = false
  private var cancelledId: String? = null

  fun requesting() {
    createInFlight = true
  }

  /** Returns the exact newly created response when an earlier Cancel must be applied. */
  fun created(id: String): String? {
    responseId = id
    createInFlight = false
    val cancel = cancelOnCreation
    cancelOnCreation = false
    return if (cancel) claimCancellation(id) else null
  }

  fun cancel(): String? {
    if (createInFlight) cancelOnCreation = true
    return responseId?.let(::claimCancellation)
  }

  private fun claimCancellation(id: String): String? {
    if (cancelledId == id) return null
    cancelledId = id
    return id
  }

  fun completed(id: String) {
    if (responseId == id) responseId = null
  }
}
