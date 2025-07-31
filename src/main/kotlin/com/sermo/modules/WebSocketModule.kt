package com.sermo.modules

import com.sermo.services.AudioStreamingPipeline
import com.sermo.services.AudioStreamingPipelineImpl
import com.sermo.websocket.ConnectionManager
import com.sermo.websocket.MessageRouter
import com.sermo.websocket.WebSocketHandler
import org.koin.dsl.module

val webSocketModule =
    module {
        single<ConnectionManager> { ConnectionManager() }
        single<MessageRouter> { MessageRouter() }
        single<AudioStreamingPipeline> { AudioStreamingPipelineImpl(get()) } // Add this line
        single<WebSocketHandler> { WebSocketHandler(get(), get(), get()) }
    }
