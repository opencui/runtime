package io.opencui.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.*
import io.opencui.core.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import reactor.core.publisher.Mono
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ScalarContainer<T>(val returnValue : T): Serializable{}

data class PostgrestConnection(val cfg: Configuration) : IConnection {
    val mapper = ObjectMapper()

    val url = cfg.url
    val schemaName = cfg["schemaName"]!! as String
    val pattern1 = "yyyy-MM-dd"
    val pattern2 = "yyyy-MM-dd HH:mm:ss"
    val pattern3 = "HH:mm:ss"
    val client = WebClient.builder()
        .baseUrl(cfg.url)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun close() {
    }

    inline fun <reified T> post(path: String, payload: JsonNode): T? {
        val response = client.post()
            .uri("""/api/rpc/$path""")
            .header("Content-Profile",  schemaName)
            .body(Mono.just(payload), JsonNode::class.java)
            .retrieve()
            .bodyToMono(T::class.java)
        return response.block()
    }

    @Throws(ProviderInvokeException::class)
    fun invoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String): JsonElement? {
        val path = functionMeta["path"]!!
        val bodyJson = mapper.readTree(calcBody(context))
        val result = try {
            post<JsonElement>(path, bodyJson)
        } catch (e: Exception) {
            logger.error("fail to exec postgrest query : with $bodyJson on $url/api/rpc/$path when $cfg")
            throw ProviderInvokeException(e.message ?: "no message")
        }

        logger.debug("Postgrest Provider result : ${result.toString()}")
        return result
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> svInvoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String, converter: Converter<T>): T {
        val result = invoke(functionMeta, context, body)
        logger.debug("Postgrest Provider result : ${result.toString()}")
        val data = result?.get(0)
        return if (data == null) null as T else converter(data["data"])
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> mvInvoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String, converter: Converter<T>): List<T> {
        val result = invoke(functionMeta, context, body)
        val results = mutableListOf<T>()
        assert(result is ArrayNode)
        result?.map { results.add (converter(it["data"]))}
        return results
    }

    fun calcBody(params: Map<String, Any?>): String {
        val map = mutableMapOf<String, Any?>()
        for(entry in params){
            val key = entry.key
            val value = entry.value
            if(value is LocalDate || value is LocalDateTime || value is LocalTime){
                if(map.isEmpty()) map.putAll(params)
                if(value is LocalDate){
                    map[key] = DateTimeFormatter.ofPattern(pattern1).format(value)
                    continue
                }
                if(value is LocalDateTime){
                    map[key] = DateTimeFormatter.ofPattern(pattern2).format(value)
                    continue
                }
                if(value is LocalTime){
                    map[key] = DateTimeFormatter.ofPattern(pattern3).format(value)
                    continue
                }
            }
        }
        if(map.isEmpty()) map.putAll(params)
        return  mapper.writeValueAsString(map)
    }

    companion object{
        val logger = LoggerFactory.getLogger(PostgrestConnection::class.java)
    }
}

data class RestfulConnection(val cfg: Configuration) : IConnection {
    val url = cfg.url
    val client = WebClient.builder()
        .baseUrl(cfg.url)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    inline fun <reified T> post(path: String, payload: JsonNode, headers: Map<String, String>, ): T? {
        val response = client.post()
            .uri("""/api/rpc/$path""")
            .body(Mono.just(payload), JsonNode::class.java)
            .retrieve()
            .bodyToMono(T::class.java)
        return response.block()
    }

    @Throws(ProviderInvokeException::class)
    fun invoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String): JsonObject {
        val method = functionMeta["method"] ?: "POST"
        val path = functionMeta["path"]!!
        val bodyJson = Json.decodeFromString<JsonObject>(body)
        val result = try {
            when(method) {
                "POST" -> {
                    val target = client.post().uri("""/api/rpc/$path""")
                    for( (k,v) in cfg) {
                        target.header(k, v.toString())
                    }
                    target.body(Mono.just(bodyJson), JsonNode::class.java)
                        .retrieve()
                        .bodyToMono(JsonObject::class.java).block()!!
                }

                else -> {
                    throw NotImplementedError("Only support POST for now.")
                }
            }
        } catch (e: Exception) {
            logger.error("fail to exec restful query : $url with $body")
            throw ProviderInvokeException(e.message ?: "no message")
        }
        logger.debug(result.toPrettyString())
        return result
    }


    @Throws(ProviderInvokeException::class)
    override fun <T> svInvoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String, converter: Converter<T>): T {
        val result = invoke(functionMeta, context, body)
        return converter(result)
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> mvInvoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String, converter: Converter<T>): List<T> {
        // TODO(Not Implemented)
        val result = invoke(functionMeta, context, body)
        assert(result is ArrayNode)
        val results = mutableListOf<T>()
        result.map { converter(it) }
        return results
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object{
        val logger = LoggerFactory.getLogger(RestfulConnection::class.java)
    }
}
