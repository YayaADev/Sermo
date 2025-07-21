package com.sermo.models

class ValidationException(
    val code: String,
    override val message: String
) : RuntimeException(message)