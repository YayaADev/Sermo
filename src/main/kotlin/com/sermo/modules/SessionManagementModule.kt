package com.sermo.modules

import com.sermo.session.SessionContextRegistry
import com.sermo.session.SessionCoordinator
import com.sermo.session.SessionEventBus
import com.sermo.websocket.WebSocketEventRelay
import org.koin.dsl.module

val sessionManagementModule =
    module {
        single(createdAtStart = true) { SessionEventBus() }
        single(createdAtStart = true) { SessionContextRegistry() }
        single(createdAtStart = true) { SessionCoordinator(get(), get()) }

        single(createdAtStart = true) { WebSocketEventRelay(get(), get()) }
    }
