package com.sermo.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Updated session coordinator using centralized context registry
 */
class SessionCoordinator(
    private val eventBus: SessionEventBus,
    private val contextRegistry: SessionContextRegistry,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SessionCoordinator::class.java)
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    init {
        // Subscribe to session events for monitoring
        coordinatorScope.launch {
            eventBus.eventFlow.collect { event ->
                handleSessionEvent(event)
            }
        }
    }

    /**
     * Creates a new session - now creates context automatically
     */
    suspend fun createSession(
        sessionId: String,
        languageCode: String,
        clientInfo: Map<String, String> = emptyMap(),
    ): Result<SessionContext> {
        if (isShutdown.get()) {
            return Result.failure(Exception("Session coordinator is shutdown"))
        }

        // Create context in registry
        val contextResult = contextRegistry.createContext(sessionId, languageCode, clientInfo)
        if (contextResult.isFailure) {
            return contextResult
        }

        val context = contextResult.getOrThrow()

        // Publish session created event
        eventBus.publishEvent(
            SessionCreatedEvent(
                sessionId = sessionId,
                languageCode = languageCode,
                clientInfo = clientInfo,
            ),
        )

        logger.info("Session created with context: $sessionId")
        return Result.success(context)
    }

    /**
     * Terminates a session - single point of cleanup
     */
    suspend fun terminateSession(
        sessionId: String,
        reason: SessionTerminationReason,
    ): Result<Unit> {
        try {
            val context = contextRegistry.getContext(sessionId)
            if (context == null) {
                logger.debug("Session $sessionId not found for termination")
                return Result.success(Unit)
            }

            // Calculate duration
            val duration = context.getDuration().milliseconds

            // Mark as inactive
            context.isActive.set(false)

            // Publish termination event - this triggers all service cleanups
            eventBus.publishEvent(
                SessionTerminatedEvent(
                    sessionId = sessionId,
                    reason = reason,
                    duration = duration,
                ),
            )

            // Remove from registry - this is the ONLY manual cleanup needed!
            contextRegistry.removeContext(sessionId)

            logger.info("Session terminated: $sessionId, reason: $reason, duration: ${duration.inWholeSeconds}s")
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate session $sessionId", e)
            return Result.failure(e)
        }
    }

    /**
     * Gets session context
     */
    fun getSessionContext(sessionId: String): SessionContext? {
        return contextRegistry.getContext(sessionId)
    }

    /**
     * Checks if session is active
     */
    fun isSessionActive(sessionId: String): Boolean {
        return contextRegistry.isSessionActive(sessionId)
    }

    /**
     * Records activity
     */
    fun recordActivity(sessionId: String) {
        contextRegistry.updateActivity(sessionId)
    }

    /**
     * Handle session events
     */
    private suspend fun handleSessionEvent(event: SessionEvent) {
        try {
            when (event) {
                is SessionErrorEvent -> {
                    logger.warn("Session error in ${event.serviceName}: ${event.errorMessage}")
                }
                else -> {
                    // Update activity for all events
                    contextRegistry.updateActivity(event.sessionId)
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling session event: ${event.javaClass.simpleName}", e)
        }
    }

    /**
     * Shutdown
     */
    suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down session coordinator...")

        // Terminate all active sessions
        contextRegistry.getActiveSessions().forEach { context ->
            terminateSession(context.sessionInfo.sessionId, SessionTerminationReason.SHUTDOWN)
        }

        contextRegistry.shutdown()
        coordinatorScope.cancel()
        logger.info("Session coordinator shutdown complete")
    }
}
