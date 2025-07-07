package com.sermo.modules

import org.koin.dsl.module

val appModule = module {
    includes(googleCloudModule)
    includes(sermoModule)
}