package io.opencui.du

import io.opencui.serialization.*
import kotlin.collections.HashMap


// Language independent slot meta.
interface SchemaSlotMeta{
    val label: String
    val type: String
    val isMultiValue: Boolean
    val parent: String?
    val isHead: Boolean
}

/**
 * This is mainly used as raw source of the information.
 * isHead is created to indicate whether current slot is head of the hosting frame.
 */
// TODO (xiaobo): please fill the triggers and prompts, so that DU have enough information.
data class DUSlotMeta(
        val label: String,                           // this is language independent.
        val triggers: List<String> = emptyList(),    // this is language dependent.
        val type: String?=null,
        // TODO (@flora default false, compiler need to pass annotation from platform)
        val isMultiValue: Boolean? = false,
        val parent: String? = null,
        val isHead: Boolean = false) {
    // Used to capture whether this slot is mentioned in the expression.
    var isMentioned: Boolean = false
    var prefixes: Set<String>?=null
    var suffixes: Set<String>?=null
}


fun extractSlotSurroundingWords(exprOwners: JsonArray, entities: Set<String>):
        Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
    // frame#slot: prefixes
    val slotPrefixes : MutableMap<String, MutableSet<String>> = HashMap()
    // frame#slot: suffixes
    val slotSuffixes : MutableMap<String, MutableSet<String>> = HashMap()
    // frame dontcare annotations
    for (owner in exprOwners) {
        val ownerId = ((owner as JsonObject).get("owner_id")as JsonPrimitive).content()
        if( !entities.contains(ownerId)) continue
        // entity dontcare annotations has been processed in above loop
        val expressions = owner["expressions"]!! as JsonArray
        for (expression in expressions) {
            val exprObject = expression as JsonObject
            val utterance = (exprObject.get("utterance")!! as JsonPrimitive).content().removeSuffix(".")
            val contextObject = exprObject.get("context")!! as JsonObject
            val frame_id = (contextObject.get("frame_id")!! as JsonPrimitive).content()
            val attribute_id = (contextObject.get("attribute_id")!! as JsonPrimitive).content()
            val parts = utterance.split(' ')
            for ((i, part) in parts.withIndex()) {
                if (part.length > 2 && part.startsWith('$') && part.endsWith('$')) {
                    val key = "$frame_id#$attribute_id"
                    if (i > 0 && !parts[i-1].startsWith('$')) {
                        if (!slotPrefixes.containsKey(key)) slotPrefixes[key] = mutableSetOf()
                        slotPrefixes[key]!!.add(parts[i-1])
                    }
                    if (i < parts.size-1 && !parts[i+1].startsWith('$')) {
                        if (!slotSuffixes.containsKey(key)) slotSuffixes[key] = mutableSetOf()
                        slotSuffixes[key]!!.add(parts[i+1])
                    }
                }
            }
        }
    }
    return Pair(slotPrefixes, slotSuffixes)
}


interface DUMeta {
    fun getLang(): String
    fun getLabel(): String
    fun getVersion(): String { return ""}
    fun getBranch(): String { return "master"}
    fun getOrg(): String { return "" }
    fun getTimezone(): String { return "america/los_angeles" }

    fun getFrameExpressions(): JsonArray

    fun getEntities(): Set<String>

    fun getEntityInstances(name: String): Map<String, List<String>> // for runtime, all labels and normalized form

    fun getTriggers(name: String): List<String> {
        return listOf()
    }
    fun getEntityMeta(name: String): IEntityMeta? // string encoding of JsonArray of JsonObject

    fun getSlotMetas(frame: String) : List<DUSlotMeta>

    fun isEntity(name: String) : Boolean  // given a name, return true if it's entity

    // TODO(xiaobo): to support head on frame, just make this this function work with entity type.
    fun getSubFrames(fullyQualifiedType: String): List<String> { return emptyList() }

    companion object {
        const val OWNERID = "owner_id"
        const val EXPRESSIONS = "expressions"
        const val CONTEXT = "context"
        const val TYPEID = "frame_id" // this is type id.
        const val UTTERANCE = "utterance"
    }
}

fun DUMeta.getSlotMeta(frame:String, slot:String) : DUSlotMeta? {
    return getSlotMetas(frame).firstOrNull {it.label == slot}
}

fun DUMeta.getSlotType(frame: String, slot:String) : String {
    return getSlotMetas(frame).firstOrNull {it.label == slot}?.type ?: ""
}


fun DUMeta.getEntitySlotMetaRecursively(frame:String, slot:String): DUSlotMeta? {
    // Including all the top level slots.
    val slotsMetaMap = getSlotMetas(frame).map { it.label to it }.toMap().toMutableMap()
    if (!slotsMetaMap.containsKey(slot)) return null

    // We now handle the qualified ones here (a.b.c)
    val qualified = slot.split(".")
    var frameType = frame
    for (simpleName in qualified.subList(0, qualified.size - 1)) {
        frameType = getSlotType(frameType, simpleName)
        if (frameType == "") return null
    }
    return getSlotMetas(frameType).find { it.label == qualified[qualified.size - 1] }?.copy()
}

abstract class DslDUMeta() : DUMeta {
    abstract val entityTypes: Map<String, EntityType>
    abstract val slotMetaMap: Map<String, List<DUSlotMeta>>
    abstract val aliasMap: Map<String, List<String>>
    val subtypes: MutableMap<String, List<String>> = mutableMapOf()
    override fun getSubFrames(fullyQualifiedType: String): List<String> {
        return subtypes[fullyQualifiedType] ?: emptyList()
    }

    override fun getEntities(): Set<String> {
        return entityTypes.keys
    }

    override fun getTriggers(name: String): List<String> {
        return aliasMap[name] ?: listOf()
    }

    override fun getEntityMeta(name: String): IEntityMeta? {
        return entityTypes[name]
    }

    override fun getSlotMetas(frame: String): List<DUSlotMeta> {
        return slotMetaMap[frame] ?: listOf()
    }

    override fun isEntity(name: String): Boolean {
        return entityTypes.containsKey(name)
    }
}


abstract class JsonDUMeta() : DUMeta {
    abstract val entityMetas: Map<String, EntityMeta>
    abstract val slotMetaMap: Map<String, List<DUSlotMeta>>
    abstract val aliasMap: Map<String, List<String>>
    val subtypes: MutableMap<String, List<String>> = mutableMapOf()
    override fun getSubFrames(fullyQualifiedType: String): List<String> {
        return subtypes[fullyQualifiedType] ?: emptyList()
    }

    override fun getEntities(): Set<String> {
        return entityMetas.keys
    }

    override fun getTriggers(name: String): List<String> {
        return aliasMap[name] ?: listOf()
    }

    override fun getEntityMeta(name: String): IEntityMeta? {
        return entityMetas[name]
    }

    override fun getSlotMetas(frame: String): List<DUSlotMeta> {
        return slotMetaMap[frame] ?: listOf()
    }

    override fun isEntity(name: String): Boolean {
        return entityMetas.containsKey(name)
    }
}

fun DUMeta.getEntitySlotTypeRecursively(frame: String, slot: String?): String? {
    if (slot == null) return null
    return getEntitySlotMetaRecursively(frame, slot)?.type
}


interface IEntityMeta {
    val recognizer: List<String>
    val children: List<String>
}

data class EntityMeta(
    override val recognizer: List<String>,
    val parents: List<String> = emptyList(),
    override val children: List<String> = emptyList()
) : java.io.Serializable, IEntityMeta

data class EntityType(
    val label: String,
    override val recognizer: List<String>,
    val entities: Map<String, List<String>>,
    val parent: String? = null,
    override val children: List<String> = emptyList()): IEntityMeta
