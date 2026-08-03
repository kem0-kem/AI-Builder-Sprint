package com.apptive.slowtalk.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import retrofit2.Response

internal val apiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

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

@Serializable
data class ModerationPendingDto(
    val moderationStatus: String,
    val submissionId: String,
)

sealed interface ModeratedApiResult<out T> {
    data class Resource<T>(val data: T) : ModeratedApiResult<T>
    data class Pending(val moderation: ModerationPendingDto) : ModeratedApiResult<Nothing>
}

class ModerationPendingException(
    val moderation: ModerationPendingDto,
) : IllegalStateException("Content is pending moderation: ${moderation.submissionId}")

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

fun <T> ModeratedApiResult<T>.requireResource(): T = when (this) {
    is ModeratedApiResult.Resource -> data
    is ModeratedApiResult.Pending -> throw ModerationPendingException(moderation)
}

internal fun <T> decodeModeratedEnvelope(
    statusCode: Int,
    envelope: ApiEnvelope<JsonElement>,
    serializer: KSerializer<T>,
    json: Json = apiJson,
): ModeratedApiResult<T> {
    val data = envelope.requireData()
    return if (statusCode == 202) {
        ModeratedApiResult.Pending(json.decodeFromJsonElement(ModerationPendingDto.serializer(), data))
    } else {
        ModeratedApiResult.Resource(json.decodeFromJsonElement(serializer, data))
    }
}

suspend fun <T> apiData(
    json: Json = apiJson,
    call: suspend () -> ApiEnvelope<T>,
): T = try {
    call().requireData()
} catch (exception: HttpException) {
    throw exception.toApiEnvelopeException(json)
}

suspend fun apiUnit(
    json: Json = apiJson,
    call: suspend () -> Response<Unit>,
) {
    val response = call()
    if (!response.isSuccessful) throw response.toApiEnvelopeException(json)
}

suspend fun <T> apiModerated(
    serializer: KSerializer<T>,
    json: Json = apiJson,
    call: suspend () -> Response<ApiEnvelope<JsonElement>>,
): ModeratedApiResult<T> {
    val response = call()
    if (!response.isSuccessful) throw response.toApiEnvelopeException(json)
    val envelope = response.body() ?: throw invalidResponse("The API returned an empty response body.")
    return decodeModeratedEnvelope(response.code(), envelope, serializer, json)
}

private fun HttpException.toApiEnvelopeException(json: Json): ApiEnvelopeException =
    response()?.toApiEnvelopeException(json)
        ?: invalidResponse("HTTP ${code()} returned without a response body.")

private fun Response<*>.toApiEnvelopeException(json: Json): ApiEnvelopeException {
    val parsed = errorBody()?.string()?.let { body ->
        runCatching { json.decodeFromString<ApiEnvelope<JsonElement>>(body) }.getOrNull()
    }
    return ApiEnvelopeException(
        parsed?.error ?: ApiErrorDto(
            code = "HTTP_ERROR",
            message = "HTTP ${code()} ${message()}".trim(),
        ),
    )
}

private fun invalidResponse(message: String): ApiEnvelopeException = ApiEnvelopeException(
    ApiErrorDto(code = "INVALID_API_RESPONSE", message = message),
)
