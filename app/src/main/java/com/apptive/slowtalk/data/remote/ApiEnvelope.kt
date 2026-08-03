package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiErrorDto? = null,
    val meta: ApiMeta? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)

@Serializable
data class ApiMeta(
    val nextCursor: String? = null,
    val hasNext: Boolean? = null,
)

class ApiEnvelopeException(
    val error: ApiErrorDto,
) : IllegalStateException("${error.code}: ${error.message}")

fun <T> ApiEnvelope<T>.requireData(): T {
    if (!ok) {
        throw ApiEnvelopeException(
            error ?: ApiErrorDto(
                code = "UNKNOWN_API_ERROR",
                message = "The API returned an unsuccessful response without error details.",
            ),
        )
    }
    return data ?: throw ApiEnvelopeException(
        ApiErrorDto(
            code = "INVALID_API_RESPONSE",
            message = "The API returned a successful response without data.",
        ),
    )
}
