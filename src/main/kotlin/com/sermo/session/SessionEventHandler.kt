package com.sermo.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interface for services that need to handle session events
 */
interface SessionEventHandler {
    /**
     * Called when a session is created
     */
    suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit>

    /**
     * Called when a session is terminated - must clean up all resources
     */
    suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit>

    /**
     * Called when a session error occurs
     */
    suspend fun onSessionError(event: SessionErrorEvent): Result<Unit> {
        // Default implementation - services can override if needed
        return Result.success(Unit)
    }

    /**
     * Service name for logging and identification
     */
    val serviceName: String
}

/**
 * Base implementation for session-aware services
 */
abstract class SessionAwareService(
    protected val eventBus: SessionEventBus,
    override val serviceName: String,
) : SessionEventHandler {
    val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    init {
        // Subscribe to session events
        serviceScope.launch {
            eventBus.subscribeToTerminations().collect { event ->
                try {
                    onSessionTerminated(event)
                } catch (e: Exception) {
                    logger.error("Error handling session termination in $serviceName", e)
                }
            }
        }

        serviceScope.launch {
            eventBus.subscribeToEventType<SessionErrorEvent>().collect { event ->
                try {
                    onSessionError(event)
                } catch (e: Exception) {
                    logger.error("Error handling session error in $serviceName", e)
                }
            }
        }
    }

    /**
     * Publishes an event to the bus
     */
    protected fun publishEvent(event: SessionEvent) {
        eventBus.publishEvent(event)
    }

    /**
     * Publishes an error event
     */
    protected fun publishError(
        sessionId: String,
        errorType: String,
        errorMessage: String,
    ) {
        publishEvent(
            SessionErrorEvent(
                sessionId = sessionId,
                errorType = errorType,
                errorMessage = errorMessage,
                serviceName = serviceName,
            ),
        )
    }

    /**
     * Shutdown the service
     */
    open suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }
        serviceScope.cancel()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SessionAwareService::class.java)
    }
}
