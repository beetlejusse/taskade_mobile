package com.app.taskade_mobile.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * A tiny Retrofit [Converter.Factory] backed by kotlinx.serialization — the same
 * job the external `retrofit2-kotlinx-serialization-converter` does, kept in-tree
 * so the only serialization dependency is `kotlinx-serialization-json` (one fewer
 * third-party artifact to resolve).
 *
 * Resolves the [kotlinx.serialization.KSerializer] for each request/response type
 * from the [Json] instance's serializers module and (de)serializes the body.
 */
class KotlinxJsonConverterFactory(
    private val json: Json,
    private val contentType: MediaType
) : Converter.Factory() {

    @OptIn(ExperimentalSerializationApi::class)
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *> {
        val serializer = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any?> { body ->
            body.use { json.decodeFromString(serializer, it.string()) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody> {
        val serializer = json.serializersModule.serializer(type)
        return Converter<Any?, RequestBody> { value ->
            json.encodeToString(serializer, value).toRequestBody(contentType)
        }
    }
}
