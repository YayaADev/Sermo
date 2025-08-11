package com.sermo.modules

import com.sermo.websocket.ConnectionManager
import com.sermo.websocket.MessageRouter
import com.sermo.websocket.WebSocketHandler
import org.koin.dsl.module

val webSocketModule =
    module {
        // Core WebSocket infrastructure
        single<ConnectionManager> { ConnectionManager() }
        single<MessageRouter> { MessageRouter(get()) }

        // UPDATED: WebSocket handler with new session management (no old SessionLifecycleManager)
        single<WebSocketHandler> {
            WebSocketHandler(
                connectionManager = get(),
                messageRouter = get(),
                sessionCoordinator = get(),
                audioStreamingPipeline = get(),
                conversationFlowManager = get(),
                json = get(),
                eventBus = get(),
            )
        }
    }
