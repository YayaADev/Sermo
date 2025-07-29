package com.sermo.exceptions

class ValidationException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
