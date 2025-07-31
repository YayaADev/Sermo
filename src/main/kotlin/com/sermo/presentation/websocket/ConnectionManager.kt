package com.sermo.presentation.websocket

import com.sermo.shared.exceptions.WebSocketConnectionException
import com.sermo.shared.exceptions.WebSocketSessionException
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import io.ktor.websocket.WebSocketSession as KtorWebSocketSession

/**
 * Manages WebSocket connections and session lifecycle
 */
class ConnectionManager {
    private val sessions = ConcurrentHashMap<String, SermoWebSocketSession>()
    private val sessionIdGenerator = AtomicLong(0)
    private val sessionLock = Mutex()

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectionManager::class.java)
        private const val SESSION_ID_PREFIX = "session_"
    }

    /**
     * Registers a new WebSocket session and returns session ID
     */
    suspend fun registerSession(webSocketSession: KtorWebSocketSession): String {
        return sessionLock.withLock {
            val sessionId = generateSessionId()
            val session =
                SermoWebSocketSession(
                    id = sessionId,
                    webSocketSession = webSocketSession as DefaultWebSocketSession,
                    connectionTime = System.currentTimeMillis(),
                )

            sessions[sessionId] = session
            logger.info("WebSocket session registered: $sessionId")

            sessionId
        }
    }

    /**
     * Unregisters a WebSocket session
     */
    suspend fun unregisterSession(sessionId: String) {
        sessionLock.withLock {
            val session = sessions.remove(sessionId)
            if (session != null) {
                try {
                    // Close the WebSocket session properly
                    if (!session.webSocketSession.incoming.isClosedForReceive) {
                        session.webSocketSession.close()
                    }
                } catch (e: Exception) {
                    logger.warn("Error closing WebSocket session $sessionId", e)
                }
                logger.info("WebSocket session unregistered: $sessionId")
            } else {
                logger.warn("Attempted to unregister non-existent session: $sessionId")
            }
        }
    }

    /**
     * Gets a WebSocket session by ID
     */
    fun getSession(sessionId: String): SermoWebSocketSession? {
        return sessions[sessionId]
    }

    /**
     * Gets all active sessions
     */
    fun getAllSessions(): List<SermoWebSocketSession> {
        return sessions.values.toList()
    }

    /**
     * Gets the count of active sessions
     */
    fun getActiveSessionCount(): Int {
        return sessions.size
    }

    /**
     * Checks if a session exists and is active
     */
    fun isSessionActive(sessionId: String): Boolean {
        val session = sessions[sessionId]
        return session?.isActive() == true
    }

    /**
     * Cleans up inactive sessions
     */
    suspend fun cleanupInactiveSessions() {
        sessionLock.withLock {
            val inactiveSessions = sessions.values.filter { !it.isActive() }
            inactiveSessions.forEach { session ->
                sessions.remove(session.id)
                try {
                    if (!session.webSocketSession.incoming.isClosedForReceive) {
                        session.webSocketSession.close()
                    }
                } catch (e: Exception) {
                    logger.warn("Error closing session during cleanup: ${session.id}", e)
                }
                logger.info("Cleaned up inactive session: ${session.id}")
            }
        }
    }

    /**
     * Sends a message to a specific session
     */
    suspend fun sendToSession(
        sessionId: String,
        message: String,
    ) {
        val session = getSession(sessionId)
        if (session != null && session.isActive()) {
            try {
                session.webSocketSession.send(io.ktor.websocket.Frame.Text(message))
                session.updateActivity()
                logger.debug("Sent text message to session $sessionId")
            } catch (e: ClosedReceiveChannelException) {
                logger.warn("Attempted to send to closed session: $sessionId")
                unregisterSession(sessionId)
            } catch (e: Exception) {
                logger.error("Error sending message to session $sessionId", e)
                throw WebSocketSessionException("Failed to send message to session $sessionId", e)
            }
        } else {
            throw WebSocketConnectionException("Session $sessionId is not active or does not exist")
        }
    }

    /**
     * Sends binary data to a specific session
     */
    suspend fun sendBinaryToSession(
        sessionId: String,
        data: ByteArray,
    ) {
        val session = getSession(sessionId)
        if (session != null && session.isActive()) {
            try {
                session.webSocketSession.send(io.ktor.websocket.Frame.Binary(true, data))
                session.updateActivity()
                logger.debug("Sent binary message to session $sessionId, size: ${data.size} bytes")
            } catch (e: ClosedReceiveChannelException) {
                logger.warn("Attempted to send binary to closed session: $sessionId")
                unregisterSession(sessionId)
            } catch (e: Exception) {
                logger.error("Error sending binary message to session $sessionId", e)
                throw WebSocketSessionException("Failed to send binary message to session $sessionId", e)
            }
        } else {
            throw WebSocketConnectionException("Session $sessionId is not active or does not exist")
        }
    }

    /**
     * Broadcasts a message to all active sessions
     */
    suspend fun broadcastMessage(message: String) {
        val activeSessions = getAllSessions().filter { it.isActive() }
        logger.debug("Broadcasting message to ${activeSessions.size} active sessions")

        activeSessions.forEach { session ->
            try {
                sendToSession(session.id, message)
            } catch (e: Exception) {
                logger.error("Error broadcasting to session ${session.id}", e)
            }
        }
    }

    /**
     * Broadcasts binary data to all active sessions
     */
    suspend fun broadcastBinary(data: ByteArray) {
        val activeSessions = getAllSessions().filter { it.isActive() }
        logger.debug("Broadcasting binary data to ${activeSessions.size} active sessions")

        activeSessions.forEach { session ->
            try {
                sendBinaryToSession(session.id, data)
            } catch (e: Exception) {
                logger.error("Error broadcasting binary to session ${session.id}", e)
            }
        }
    }

    /**
     * Gets session statistics
     */
    fun getSessionStats(): SessionStats {
        val activeSessions = getAllSessions()
        val totalSessions = activeSessions.size
        val activeSessCount = activeSessions.count { it.isActive() }
        val avgDuration =
            if (totalSessions > 0) {
                activeSessions.map { it.getSessionDuration() }.average()
            } else {
                0.0
            }

        return SessionStats(
            totalSessions = totalSessions,
            activeSessions = activeSessCount,
            averageSessionDuration = avgDuration,
        )
    }

    /**
     * Generates a unique session ID
     */
    private fun generateSessionId(): String {
        return SESSION_ID_PREFIX + sessionIdGenerator.incrementAndGet()
    }

    /**
     * Shutdown all sessions gracefully
     */
    suspend fun shutdown() {
        logger.info("Shutting down ConnectionManager...")
        sessionLock.withLock {
            sessions.values.forEach { session ->
                try {
                    session.webSocketSession.close()
                } catch (e: Exception) {
                    logger.warn("Error closing session ${session.id} during shutdown", e)
                }
            }
            sessions.clear()
        }
        logger.info("ConnectionManager shutdown complete")
    }
}

/**
 * Represents an active WebSocket session (renamed to avoid conflict with Ktor's WebSocketSession)
 */
data class SermoWebSocketSession(
    val id: String,
    val webSocketSession: DefaultWebSocketSession,
    val connectionTime: Long,
    var lastActivity: Long = System.currentTimeMillis(),
) {
    /**
     * Checks if the WebSocket session is still active
     */
    fun isActive(): Boolean {
        return try {
            !webSocketSession.incoming.isClosedForReceive && !webSocketSession.outgoing.isClosedForSend
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Updates the last activity timestamp
     */
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }

    /**
     * Gets the session duration in milliseconds
     */
    fun getSessionDuration(): Long {
        return System.currentTimeMillis() - connectionTime
    }

    /**
     * Gets the idle time in milliseconds
     */
    fun getIdleTime(): Long {
        return System.currentTimeMillis() - lastActivity
    }

    /**
     * Checks if session has been idle for too long
     */
    fun isIdleTooLong(maxIdleTimeMs: Long): Boolean {
        return getIdleTime() > maxIdleTimeMs
    }
}

/**
 * Session statistics data class
 */
data class SessionStats(
    val totalSessions: Int,
    val activeSessions: Int,
    val averageSessionDuration: Double,
)
