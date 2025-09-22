package com.sermo.modules

import org.koin.dsl.module

/**
 * Root module of all other modules
 */
val appModule =
    module {
        includes(sermoModule)
        includes(webSocketModule)
        includes(clientsModule)
        includes(sessionManagementModule)
    }
