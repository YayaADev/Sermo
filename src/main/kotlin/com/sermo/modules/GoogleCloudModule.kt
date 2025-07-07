package com.sermo.modules

import com.google.cloud.speech.v1.SpeechClient
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val googleCloudModule = module {

    single<SpeechClient> {
        val logger = LoggerFactory.getLogger("GoogleCloudModule")

        try {
            logger.info("Creating Google Cloud Speech client...")
            val client = SpeechClient.create()
            logger.info("Google Cloud Speech client created successfully")
            client
        } catch (e: Exception) {
            logger.error(" Failed to create Google Cloud Speech client", e)
            throw e
        }
    }
}