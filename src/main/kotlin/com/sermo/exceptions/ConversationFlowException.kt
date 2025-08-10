package com.sermo.exceptions

/**
 * Exception for conversation flow errors
 */
class ConversationFlowException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
