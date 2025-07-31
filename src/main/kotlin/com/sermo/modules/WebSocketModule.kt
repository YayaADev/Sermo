package com.sermo.modules

import com.sermo.presentation.websocket.ConnectionManager
import com.sermo.presentation.websocket.MessageRouter
import com.sermo.presentation.websocket.WebSocketHandler
import org.koin.dsl.module

val webSocketModule =
    module {
        single<ConnectionManager> { ConnectionManager() }
        single<MessageRouter> { MessageRouter() }
        single<WebSocketHandler> { WebSocketHandler(get(), get()) }
    }
