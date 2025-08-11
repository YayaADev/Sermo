package com.sermo.modules

import com.sermo.session.SessionCoordinator
import com.sermo.session.SessionEventBus
import com.sermo.websocket.WebSocketEventRelay
import org.koin.dsl.module

val sessionManagementModule =
    module {
        // Core session management - EAGER SINGLETONS
        single(createdAtStart = true) { SessionEventBus() }
        single(createdAtStart = true) { SessionCoordinator(get()) }

        // Event relay for breaking circular dependencies - EAGER
        single(createdAtStart = true) { WebSocketEventRelay(get(), get()) }
    }
