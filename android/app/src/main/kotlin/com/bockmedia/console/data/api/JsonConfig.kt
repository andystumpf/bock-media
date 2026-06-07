package com.bockmedia.console.data.api

import kotlinx.serialization.json.Json

val bockJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}
