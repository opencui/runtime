package io.opencui.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import io.opencui.core.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.sql.SQLException

interface Closable {
    fun close()
}

interface IConnection: Closable {
    fun <T> invoke(
        functionMeta: Map<String, String>,
        context: Map<String, Any?>,
        body: String,
        converter: Converter<T>,
        isList: Boolean = false
    ): T
}

// Templated provider will have connection.
interface ITemplatedProvider : IProvider {
    var provider : IConnection?
}

class ProviderInvokeException(msg: String): Exception(msg)

// This connection is mainly used for make writing testing easy.
data class SqlConnection(val cfg: Configuration) : IConnection {

    val url = cfg.url
    val user = cfg["user"]!! as String
    val password = cfg["password"]!! as String

    val dbConn: java.sql.Connection

    init {
        val driver = driver()
        Class.forName(driver)
        dbConn = DriverManager.getConnection(url, user, password)
    }

    private fun driver() : String {
        val tokens = url.split(":")
        return when(tokens[1]) {
            "mysql" -> "com.mysql.cj.jdbc.Driver"
            "h2" -> "org.h2.Driver"
            else -> throw IllegalArgumentException("${tokens[1]} is not supported.")
        }
    }

    @Throws(ProviderInvokeException::class)
    override fun <T> invoke(functionMeta: Map<String, String>, context: Map<String, Any?>, body: String, converter: Converter<T>, isList: Boolean): T {
        val results = mutableListOf<JsonNode>()
        try {
            val stmt = dbConn.createStatement()
            var hasMoreResult = stmt.execute(body)
            while (hasMoreResult || stmt.updateCount != -1) {
                if (hasMoreResult) {
                    results.clear()
                    val result = stmt.resultSet
                    while (result.next()) {
                        val rsmd = result.metaData
                        val rowMap = mutableMapOf<String, JsonElement>()
                        for (k in 1..rsmd.columnCount) {
                            val columnAlias = rsmd.getColumnLabel(k)
                            val columnValue = result.getString(columnAlias)
                            // TODO: here we can only make TextNode for now
                            rowMap[columnAlias] = Json.makePrimitive(columnValue)
                        }
                        results.add(Json.makeObject(rowMap))
                    }
                }
                // getMoreResults(current) does not update updateCount here; we cannot keep current result open
                hasMoreResult = stmt.moreResults
            }
            stmt.close()
        } catch (e: SQLException) {
            logger.error("fail to exec mysql query : $body; err : ${e.message}")
            throw ProviderInvokeException(e.message ?: "no message")
        }
        val result = ArrayNode(JsonNodeFactory.instance, results)
        logger.debug("Sql Provider result : ${result.toPrettyString()}")

        return if (isList) {
            converter(result)
        } else {
            if (result.isEmpty) {
                converter(null)
            } else {
                converter(result[0])
            }
        }
    }

    override fun close() {
        dbConn.close()
    }

    companion object{
        val logger = LoggerFactory.getLogger(SqlConnection::class.java)
    }
}


