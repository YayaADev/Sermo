package com.sermo.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event bus for session-related events
 * Provides pub/sub mechanism for decoupled session management
 */
class SessionEventBus {
    companion object {
        private val logger = LoggerFactory.getLogger(SessionEventBus::class.java)
        const val EVENT_BUFFER_SIZE = 100
    }

    val eventBusScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    private val _eventFlow =
        MutableSharedFlow<SessionEvent>(
            replay = 0,
            extraBufferCapacity = EVENT_BUFFER_SIZE,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    val eventFlow: SharedFlow<SessionEvent> = _eventFlow.asSharedFlow()

    /**
     * Publishes an event to the bus
     */
    fun publishEvent(event: SessionEvent) {
        if (isShutdown.get()) {
            logger.warn("Attempted to publish event to shutdown event bus: ${event.javaClass.simpleName}")
            return
        }

        val emitted = _eventFlow.tryEmit(event)
        if (!emitted) {
            logger.warn("Failed to emit event (buffer full): ${event.javaClass.simpleName} for session ${event.sessionId}")
        } else {
            logger.debug("Published event: ${event.javaClass.simpleName} for session ${event.sessionId}")
        }
    }

    /**
     * Subscribe to all events for a specific session
     */
    fun subscribeToSession(sessionId: String): SharedFlow<SessionEvent> {
        val filteredFlow =
            MutableSharedFlow<SessionEvent>(
                replay = 0,
                extraBufferCapacity = EVENT_BUFFER_SIZE,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        eventBusScope.launch {
            eventFlow
                .filter { it.sessionId == sessionId }
                .collect { filteredFlow.emit(it) }
        }

        return filteredFlow.asSharedFlow()
    }

    /**
     * Subscribe to specific event types - FIXED IMPLEMENTATION
     */
    inline fun <reified T : SessionEvent> subscribeToEventType(): SharedFlow<T> {
        val filteredFlow =
            MutableSharedFlow<T>(
                replay = 0,
                extraBufferCapacity = EVENT_BUFFER_SIZE,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        eventBusScope.launch {
            eventFlow
                .filter { it is T }
                .map { it as T }
                .collect { filteredFlow.emit(it) }
        }

        return filteredFlow.asSharedFlow()
    }

    /**
     * Subscribe to termination events
     */
    fun subscribeToTerminations(): SharedFlow<SessionTerminatedEvent> {
        return subscribeToEventType<SessionTerminatedEvent>()
    }

    /**
     * Subscribe with custom filter
     */
    fun subscribeWithFilter(filter: (SessionEvent) -> Boolean): SharedFlow<SessionEvent> {
        val filteredFlow =
            MutableSharedFlow<SessionEvent>(
                replay = 0,
                extraBufferCapacity = EVENT_BUFFER_SIZE,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        eventBusScope.launch {
            eventFlow
                .filter(filter)
                .collect { filteredFlow.emit(it) }
        }

        return filteredFlow.asSharedFlow()
    }

    /**
     * Shutdown the event bus
     */
    fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down session event bus...")
        eventBusScope.cancel()
        logger.info("Session event bus shutdown complete")
    }
}
