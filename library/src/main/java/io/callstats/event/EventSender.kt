package io.callstats.event

import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.*
import java.util.concurrent.ThreadPoolExecutor

/**
 * Event queue for sending events to server
 */
class EventSender(
    private val client: OkHttpClient,
    private val executor: ThreadPoolExecutor,
    private val appID: String,
    private val confID: String,
    private val localID: String,
    private val deviceID: String,
    private val originID: String? = null,
    private val gson: Gson = Gson()) {

  private var token: String? = null
  private var ucID: String? = null

  // queue to wait before state is ready
  internal val authenticatedQueue = LinkedList<Event>()
  internal val sessionQueue = LinkedList<Event>()

  fun send(event: Event) {
    event.localID = localID
    event.deviceID = deviceID
    event.originID = originID

    // set timestamp only if it is zero
    if (event.timestamp == 0L) event.timestamp = Date().time

    // if event needs session but not available yet, put in the queue
    if (event is SessionEvent && ucID == null) {
      sessionQueue.add(event)
      return
    }

    // if event needs auth but not available yet, put in the queue
    if (event is AuthenticatedEvent && token == null) {
      authenticatedQueue.add(event)
      return
    }

    // apply session information
    (event as? AuthenticatedEvent)?.let {
      it.appID = appID
      it.confID = confID
      it.token = token
    }
    (event as? SessionEvent)?.ucID = ucID

    // send event
    val runnable = EventSendingRunnable(client, event, gson)
    runnable.callback = { sentEvent, success, response ->
      if (success && response != null) {
        if (sentEvent is AuthenticationEvent) {
          token = response["access_token"] as String
          sendAllInQueue(authenticatedQueue)
        } else if (sentEvent is CreateSessionEvent) {
          ucID = response["ucID"] as String
          sendAllInQueue(sessionQueue)
        }
      }
    }
    executor.execute(runnable)
  }

  private fun sendAllInQueue(queue: LinkedList<Event>) {
    var event = queue.poll()
    while (event != null) {
      send(event)
      event = queue.poll()
    }
  }
}