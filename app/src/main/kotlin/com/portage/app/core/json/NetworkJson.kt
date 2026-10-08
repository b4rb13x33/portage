package com.portage.app.core.json

import kotlinx.serialization.json.Json

/**
 * Single JSON configuration for the whole app.
 *
 * `ignoreUnknownKeys` is required: providers routinely add response fields we
 * do not model, and a new field must never break an existing chat stream.
 */
val networkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}
