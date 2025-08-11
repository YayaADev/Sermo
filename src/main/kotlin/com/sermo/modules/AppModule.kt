package com.sermo.modules

import org.koin.dsl.module

val appModule =
    module {
        includes(sermoModule)
        includes(webSocketModule)
        includes(clientsModule)
        includes(sessionManagementModule)
    }
