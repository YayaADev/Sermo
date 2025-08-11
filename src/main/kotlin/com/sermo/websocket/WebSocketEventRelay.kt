package com.sermo.websocket

import com.sermo.session.ConversationStateChangedEvent
import com.sermo.session.FinalTranscriptEvent
import com.sermo.session.PartialTranscriptEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.TTSAudioChunkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Handles transcript and conversation state events to send via WebSocket
 * UPDATED: Also handles TTS audio chunk events
 */
class WebSocketEventRelay(
    private val webSocketHandler: WebSocketHandler,
    private val eventBus: SessionEventBus,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(WebSocketEventRelay::class.java)
    }

    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Subscribe to transcript events
        relayScope.launch {
            eventBus.subscribeToEventType<PartialTranscriptEvent>().collect { event ->
                try {
                    webSocketHandler.sendPartialTranscript(
                        sessionId = event.sessionId,
                        transcript = event.transcript,
                        confidence = event.confidence,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to relay partial transcript for session ${event.sessionId}", e)
                }
            }
        }

        relayScope.launch {
            eventBus.subscribeToEventType<FinalTranscriptEvent>().collect { event ->
                try {
                    webSocketHandler.sendFinalTranscript(
                        sessionId = event.sessionId,
                        transcript = event.transcript,
                        confidence = event.confidence,
                        languageCode = event.languageCode,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to relay final transcript for session ${event.sessionId}", e)
                }
            }
        }

        relayScope.launch {
            eventBus.subscribeToEventType<ConversationStateChangedEvent>().collect { event ->
                try {
                    webSocketHandler.sendConversationState(
                        sessionId = event.sessionId,
                        state = event.state,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to relay conversation state for session ${event.sessionId}", e)
                }
            }
        }

        // NEW: Subscribe to TTS audio chunk events
        relayScope.launch {
            eventBus.subscribeToEventType<TTSAudioChunkEvent>().collect { event ->
                try {
                    webSocketHandler.sendTTSAudio(
                        sessionId = event.sessionId,
                        audioData = event.audioData,
                    )

                    // If this is the last chunk, update conversation state back to listening
                    if (event.isLast) {
                        webSocketHandler.sendConversationState(
                            sessionId = event.sessionId,
                            state = ConversationState.LISTENING,
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Failed to relay TTS audio for session ${event.sessionId}", e)
                }
            }
        }
    }

    fun shutdown() {
        relayScope.cancel()
    }
}
