package com.kernfolio.controller

import org.springframework.validation.BindingResult

data class FormErrors(private val result: BindingResult?) {
    fun has(field: String): Boolean = result?.hasFieldErrors(field) == true
    fun get(field: String): String? = result?.getFieldError(field)?.defaultMessage
    fun hasGlobalErrors(): Boolean = result?.hasGlobalErrors() == true
    fun globalErrors(): List<String> = result?.globalErrors?.mapNotNull { it.defaultMessage } ?: emptyList()
}
