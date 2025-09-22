package com.sermo.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized registry for all session contexts
 * Single source of truth for session state management
 */
class SessionContextRegistry {
    companion object {
        private val logger = LoggerFactory.getLogger(SessionContextRegistry::class.java)
        private const val MAX_CONCURRENT_SESSIONS = 10
    }

    private val contexts = ConcurrentHashMap<String, SessionContext>()
    private val registryMutex = Mutex()

    /**
     * Creates a new session context
     */
    suspend fun createContext(
        sessionId: String,
        languageCode: String,
        clientInfo: Map<String, String> = emptyMap(),
    ): Result<SessionContext> {
        return registryMutex.withLock {
            try {
                // Check session limit
                if (contexts.size >= MAX_CONCURRENT_SESSIONS) {
                    return Result.failure(Exception("Maximum concurrent sessions reached"))
                }

                // Check if already exists
                contexts[sessionId]?.let {
                    logger.warn("Session context already exists: $sessionId")
                    return Result.success(it)
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

                // Create context with all sub-states
                val context = SessionContext(sessionInfo = sessionInfo)
                contexts[sessionId] = context

                logger.info("Created session context: $sessionId (total: ${contexts.size})")
                Result.success(context)
            } catch (e: Exception) {
                logger.error("Failed to create session context: $sessionId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Gets a session context
     */
    fun getContext(sessionId: String): SessionContext? {
        return contexts[sessionId]
    }

    /**
     * Gets a session context or throws
     */
    fun requireContext(sessionId: String): SessionContext {
        return contexts[sessionId]
            ?: throw IllegalStateException("Session context not found: $sessionId")
    }

    /**
     * Removes a session context - this is the ONLY cleanup needed
     */
    suspend fun removeContext(sessionId: String): SessionContext? {
        return registryMutex.withLock {
            val removed = contexts.remove(sessionId)
            if (removed != null) {
                logger.info("Removed session context: $sessionId (remaining: ${contexts.size})")
            }
            removed
        }
    }

    /**
     * Updates activity for a session
     */
    fun updateActivity(sessionId: String) {
        contexts[sessionId]?.updateActivity()
    }

    /**
     * Checks if session is active
     */
    fun isSessionActive(sessionId: String): Boolean {
        return contexts[sessionId]?.isActive?.get() == true
    }

    /**
     * Gets all active sessions
     */
    fun getActiveSessions(): List<SessionContext> {
        return contexts.values.filter { it.isActive.get() }
    }

    /**
     * Gets session count
     */
    fun getActiveSessionCount(): Int = contexts.size

    /**
     * Shutdown - cleanup all contexts
     */
    suspend fun shutdown() {
        registryMutex.withLock {
            contexts.clear()
            logger.info("Session context registry cleared")
        }
    }
}
