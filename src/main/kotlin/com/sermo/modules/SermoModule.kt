package com.sermo.modules

import com.sermo.services.GoogleSpeechToTextService
import com.sermo.services.SpeechToText
import org.koin.dsl.module

val sermoModule = module {
    single<SpeechToText> { GoogleSpeechToTextService(get()) }
}