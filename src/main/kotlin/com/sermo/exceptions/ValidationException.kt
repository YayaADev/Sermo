package com.sermo.exceptions

/**
 * Validation Exception thrown from API input being wrong
 */
class ValidationException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
