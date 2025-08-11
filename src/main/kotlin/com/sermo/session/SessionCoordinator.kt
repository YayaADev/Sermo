package com.sermo.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Centralized session coordinator managing all session lifecycle
 */
class SessionCoordinator(
    private val eventBus: SessionEventBus,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SessionCoordinator::class.java)
        private const val MAX_CONCURRENT_SESSIONS = 10
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)
    private val sessionMutex = Mutex()

    // Session registry - single source of truth
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()

    init {
        // Subscribe to session events for monitoring
        coordinatorScope.launch {
            eventBus.eventFlow.collect { event ->
                handleSessionEvent(event)
            }
        }
    }

    /**
     * Creates a new session
     */
    suspend fun createSession(
        sessionId: String,
        languageCode: String,
        clientInfo: Map<String, String> = emptyMap(),
    ): Result<SessionInfo> {
        if (isShutdown.get()) {
            return Result.failure(Exception("Session coordinator is shutdown"))
        }

        return sessionMutex.withLock {
            try {
                // Check session limit
                if (activeSessions.size >= MAX_CONCURRENT_SESSIONS) {
                    logger.warn("Maximum concurrent sessions reached: $MAX_CONCURRENT_SESSIONS")
                    return Result.failure(Exception("Maximum concurrent sessions reached"))
                }

                // Check if session already exists
                if (activeSessions.containsKey(sessionId)) {
                    logger.warn("Session $sessionId already exists")
                    return Result.success(activeSessions[sessionId]!!)
                }

                // Create session info
                val sessionInfo =
                    SessionInfo(
                        sessionId = sessionId,
                        languageCode = languageCode,
                        clientInfo = clientInfo,
                        state = SessionState.ACTIVE,
                        createdAt = System.currentTimeMillis(),
                    )

                activeSessions[sessionId] = sessionInfo

                // Publish session created event
                eventBus.publishEvent(
                    SessionCreatedEvent(
                        sessionId = sessionId,
                        languageCode = languageCode,
                        clientInfo = clientInfo,
                    ),
                )

                logger.info("Session created: $sessionId with language $languageCode (total active: ${activeSessions.size})")
                Result.success(sessionInfo)
            } catch (e: Exception) {
                logger.error("Failed to create session $sessionId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Terminates a session and triggers cleanup
     */
    suspend fun terminateSession(
        sessionId: String,
        reason: SessionTerminationReason,
    ): Result<Unit> {
        return sessionMutex.withLock {
            try {
                val sessionInfo = activeSessions.remove(sessionId)
                if (sessionInfo == null) {
                    logger.debug("Session $sessionId not found for termination")
                    return Result.success(Unit)
                }

                // Calculate session duration
                val duration = (System.currentTimeMillis() - sessionInfo.createdAt).milliseconds

                // Mark session as terminated
                sessionInfo.state = SessionState.TERMINATED
                sessionInfo.terminatedAt = System.currentTimeMillis()

                // Publish termination event - this triggers all service cleanups
                eventBus.publishEvent(
                    SessionTerminatedEvent(
                        sessionId = sessionId,
                        reason = reason,
                        duration = duration,
                    ),
                )

                logger.info(
                    "Session terminated: $sessionId, reason: $reason, duration: " +
                        "${duration.inWholeSeconds}s (remaining: ${activeSessions.size})",
                )
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to terminate session $sessionId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Gets session information
     */
    fun getSession(sessionId: String): SessionInfo? {
        return activeSessions[sessionId]
    }

    /**
     * Checks if session is active
     */
    fun isSessionActive(sessionId: String): Boolean {
        return activeSessions[sessionId]?.state == SessionState.ACTIVE
    }

    /**
     * Gets all active sessions
     */
    fun getActiveSessions(): List<SessionInfo> {
        return activeSessions.values.filter { it.state == SessionState.ACTIVE }
    }

    /**
     * Gets session count
     */
    fun getActiveSessionCount(): Int {
        return activeSessions.values.count { it.state == SessionState.ACTIVE }
    }

    /**
     * Updates session state
     */
    suspend fun updateSessionState(
        sessionId: String,
        state: SessionState,
    ): Result<Unit> {
        return try {
            val sessionInfo = activeSessions[sessionId]
            if (sessionInfo != null) {
                sessionInfo.state = state
                sessionInfo.lastActivity = System.currentTimeMillis()
                logger.debug("Updated session $sessionId state to $state")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Session $sessionId not found"))
            }
        } catch (e: Exception) {
            logger.error("Failed to update session state for $sessionId", e)
            Result.failure(e)
        }
    }

    /**
     * Records session activity
     */
    fun recordActivity(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.lastActivity = System.currentTimeMillis()
        }
    }

    /**
     * Handle session events for monitoring and state updates
     */
    private suspend fun handleSessionEvent(event: SessionEvent) {
        try {
            when (event) {
                is SessionCreatedEvent -> {
                    logger.debug("Handling session created event: ${event.sessionId}")
                }
                is SessionTerminatedEvent -> {
                    logger.debug("Handling session terminated event: ${event.sessionId}")
                }
                is SessionErrorEvent -> {
                    logger.warn("Session error in ${event.serviceName}: ${event.errorMessage}")
                    // Could trigger automatic session termination on critical errors
                }
                else -> {
                    // Update last activity for all other events
                    recordActivity(event.sessionId)
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling session event: ${event.javaClass.simpleName}", e)
        }
    }

    /**
     * Shutdown coordinator
     */
    suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down session coordinator...")

        sessionMutex.withLock {
            // Terminate all active sessions
            activeSessions.keys.toList().forEach { sessionId ->
                terminateSession(sessionId, SessionTerminationReason.SHUTDOWN)
            }
            activeSessions.clear()
        }

        coordinatorScope.cancel()
        logger.info("Session coordinator shutdown complete")
    }
}
