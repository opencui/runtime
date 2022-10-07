package io.opencui.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.opencui.core.IFrame
import io.opencui.core.InternalEntity
import io.opencui.core.UserSession
import java.io.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance

/**
 * This will be our serialization tool.
 * value node is jackson's primitive
 */
typealias JsonElement = JsonNode
typealias JsonPrimitive = ValueNode
typealias JsonValue = ValueNode
typealias JsonArray = ArrayNode
typealias JsonObject = ObjectNode
typealias JsonNull = NullNode

fun ObjectNode.containsKey(s:String) : Boolean {
    return this.get(s) != null
}

fun ObjectNode.getObject(key: String) : JsonObject {
    return this.get(key) as JsonObject
}

fun ObjectNode.getElement(key: String) : JsonElement {
    return this.get(key) as JsonElement
}

fun ObjectNode.getJsonObject(key: String) : JsonObject {
    return this.get(key) as JsonObject
}

fun ObjectNode.getJsonArray(key: String) : JsonArray {
    return this.get(key) as JsonArray
}

fun ObjectNode.getObject(keys: List<String>) : JsonObject {
    var obj = this
    for (key in keys ) {
        obj = obj.getObject(key) as JsonObject
    }
    return obj
}

fun ObjectNode.getPrimitive(s: String) : JsonPrimitive {
    return this.get(s) as JsonPrimitive
}

fun ObjectNode.getString(s: String) : String {
    val obj = this.get(s) as JsonPrimitive
    return obj.asText()
}

fun ObjectNode.getInteger(s: String) : Int {
    val obj = this.get(s) as JsonPrimitive
    return obj.asInt()
}
fun ValueNode.content() : String {
    return this.toString().removeSurrounding("\"");
}

interface Converter<T>: Serializable {
    operator fun invoke(o: JsonElement?) : T
}

fun deserializeInternalEntity(node: JsonNode, defaultClass: String): InternalEntity {
    when (node) {
        is ObjectNode -> {
            val keys = node.fieldNames()
            assert(keys.hasNext())
            val type = keys.next()
            val value = (node[type] as TextNode).textValue()
            return Class.forName(type).constructors.first { it.parameters.size == 1 }.newInstance(value) as InternalEntity
        }
        is ArrayNode -> {
            assert(node.size() == 2)
            val type = (node[0] as TextNode).textValue()
            val value = (node[1] as TextNode).textValue()
            return Class.forName(type).constructors.first { it.parameters.size == 1 }.newInstance(value) as InternalEntity
        }
        is TextNode -> {
            return Class.forName(defaultClass).constructors.first { it.parameters.size == 1 }.newInstance(node.textValue()) as InternalEntity
        }
        else -> error("JsonNode type not supported")
    }
}

class InterfaceInternalEntitySerializer: JsonSerializer<InternalEntity>() {
    override fun serialize(value: InternalEntity?, gen: JsonGenerator?, serializers: SerializerProvider?) {
        if (value == null) {
            gen?.writeNull()
            return
        }
        val type = value::class.qualifiedName
        gen!!.writeStartArray()
        gen.writeString(type)
        gen.writeString(value.value)
        gen.writeEndArray()
    }
}

// TODO(sean) maybe chagned to regular class so that we can take session as member.
object Json {
    val mapper = jacksonObjectMapper()
    val JsonNull = NullNode.instance

    init {
        // support the java time.
        mapper.registerModule(JavaTimeModule())
        mapper.registerKotlinModule()
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

    fun encodeToString(o: Any) : String {
        return when(o) {
            else -> mapper.writeValueAsString(o)
        }
    }

    fun <T: Any> decodeFromJsonElement(s: JsonNode, kClass: KClass<T>): T {
        return mapper.treeToValue(s, kClass.java)
    }

    fun <T: Any> decodeFromString(s: String, kClass: KClass<T>): T {
        return mapper.readValue(s, kClass.java)
    }

    inline fun <reified T> decodeFromString(s: String) : T {
        return mapper.readValue(s)
    }

    inline fun <reified T> decodeFromJsonElement(s: JsonElement) : T {
        return mapper.treeToValue(s, T::class.java)
    }

    inline fun <reified T> decodeInterfaceFromJsonElement(session: UserSession, jn: JsonElement): T {
        val clazz = session.findKClass((jn as ObjectNode).get("@class").asText())!!
        return mapper.treeToValue(jn, clazz.java) as T
    }

    fun <T> getListConverter(svConverter: Converter<T>): Converter<List<T>> {
        return object : Converter<List<T>> {
            override fun invoke(o: JsonElement?): List<T> {
                if (o == null || o !is ArrayNode) {
                    return listOf()
                }
                return o.map { svConverter(it) }
            }
            override fun toString() : String { return "FrameConverter"}
        }
    }

    fun <T> getFrameConverter(session: UserSession?, clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                (o as? ObjectNode)?.put("@class", clazz.name)
                val tmpMapper = mapper.copy()
                tmpMapper.typeFactory = tmpMapper.typeFactory.withClassLoader(clazz.classLoader)
                val res: T = tmpMapper.treeToValue(o, clazz)
                if (res is IFrame && session != null) {
                    res.session = session
                }
                return res
            }
            override fun toString() : String { return "FrameConverter"}
        }
    }

    fun <T> getEntityConverter(clazz: Class<T>): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                val t = if (o is ObjectNode) {
                    o.remove("@class")
                    o.elements().next()
                } else {
                    o
                }
                // we don't have a better way to distinguish between TextNode and ArrayNode at present,
                // both can represent entity
                val finalNode = try {
                    decodeFromString<JsonNode>(t.textValue())
                } catch (e: Exception) {
                    t
                }
                val tmpMapper = mapper.copy()
                tmpMapper.typeFactory = tmpMapper.typeFactory.withClassLoader(clazz.classLoader)
                return tmpMapper.treeToValue(finalNode, clazz)
            }
            override fun toString() : String { return "EntityConverter"}
        }
    }

    fun <T> getInterfaceConverter(session: UserSession): Converter<T> {
        return object : Converter<T> {
            override fun invoke(o: JsonElement?): T {
                if (o == null) {
                    return null as T
                }
                check(o is ObjectNode)
                val clazz = session.findKClass(o.get("@class").asText())!!
                o.remove("@class")
                val tmpMapper = mapper.copy()
                tmpMapper.typeFactory = tmpMapper.typeFactory.withClassLoader(clazz.java.classLoader)
                val res = tmpMapper.treeToValue(o, clazz.java) as T
                if (res is IFrame) {
                    res.session = session
                }
                return res
            }
            override fun toString() : String { return "InterfaceConverter"}
        }
    }

    fun <T> getConverter(session: UserSession?, clazz: Class<T>): Converter<T> {
        return if (clazz.isInterface && InternalEntity::class.java.isAssignableFrom(clazz)) {
            getInterfaceConverter(session!!)
        } else if (IFrame::class.java.isAssignableFrom(clazz)) {
            getFrameConverter(session, clazz)
        } else {
            getEntityConverter(clazz)
        }
    }

    inline fun <reified T> getConverter(session: UserSession? = null) : Converter<T> {
        return getFrameConverter(session, T::class.java)
    }

    // database closure can only yield ObjectNode, we need to strip it for entity value type
    inline fun <reified T> getStripperConverter() : Converter<T> {
        return getEntityConverter(T::class.java)
    }

    fun findMapping(s: KClass<*>, t: KClass<*>): Map<String, String> {
        val tm = ((if (t.companionObject != null) t.companionObject!!.members.firstOrNull { it.name == "mappings" } else null)?.call(t.companionObjectInstance) as? Map<String, Map<String, String>>)?.get(s.qualifiedName)
        if (tm != null) return tm
        val sm = ((if (s.companionObject != null) s.companionObject!!.members.firstOrNull { it.name == "mappings" } else null)?.call(s.companionObjectInstance) as? Map<String, Map<String, String>>)?.get(t.qualifiedName)
        if (sm != null) {
            val reverseMap = mutableMapOf<String, String>()
            for ((k, v) in sm) {
                reverseMap[v] = k
            }
        }
        return mapOf()
    }

    inline fun <reified S: IFrame, reified T: IFrame> mappingConvert(s: S) :T {
        val sourceKClass = S::class
        val targetKClass = T::class
        val mapping = findMapping(sourceKClass, targetKClass)
        val objectNode = encodeToJsonElement(s) as ObjectNode
        objectNode.remove("session")
        for ((o, n) in mapping) {
            val v = objectNode.remove(o)
            if (v != null) {
                objectNode.replace(n, v)
            }
        }
        return decodeFromJsonElement<T>(objectNode).apply { this.session = (s as IFrame).session }
    }

    fun parseToJsonElement(s: String) : JsonNode {
        return mapper.readTree(s)
    }

    fun <T> encodeToJsonElement(s: T) : JsonElement {
        return mapper.valueToTree(s)
    }

    inline fun <reified T> makePrimitive(s: T): JsonElement {
      return when (T::class) {
          Int::class -> IntNode(s as Int)
          Float::class -> FloatNode(s as Float)
          Boolean::class -> if (s as Boolean) BooleanNode.TRUE else BooleanNode.FALSE
          String::class -> TextNode(s as String)
          else -> throw RuntimeException("Not a primitive type")
      }
    }

    fun makeObject(maps: Map<String, JsonNode> = mapOf()) : ObjectNode {
        val result = ObjectNode(JsonNodeFactory.instance)
        for ((k, v) in maps.entries) {
            result.put(k, v)
        }
        return result
    }

    fun makeArray(lists: List<JsonElement> = listOf()) : JsonArray {
        val array = ArrayNode(JsonNodeFactory.instance)
        for (it in lists) array.add(it)
        return array
    }
}

inline fun  <reified T> serialize(session: T) : String {
    val byteArrayOut = ByteArrayOutputStream()
    val objectOut = ObjectOutputStream(byteArrayOut)
    objectOut.writeObject(session)
    return String(Base64.getEncoder().encode(byteArrayOut.toByteArray()))
}

inline fun <reified T> deserialize(encodedSession: String, classLoader: ClassLoader) : T? {
    val decodedSession = Base64.getDecoder().decode(encodedSession)
    val objectIn = object : ObjectInputStream(ByteArrayInputStream(decodedSession)) {
        override fun resolveClass(desc: ObjectStreamClass?): Class<*> {
            return Class.forName(desc!!.name, true, classLoader)
        }
    }
    return objectIn.readObject() as? T
}