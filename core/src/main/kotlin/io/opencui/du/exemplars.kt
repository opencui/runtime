package io.opencui.du

import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import io.opencui.serialization.JsonObject



data class ExemplarContext(val frame_id: String, val attribute_id: String)
data class Exemplar(val utterance: String, val label: String, val context: ExemplarContext)

/**
 * One of the key functionality for framely is providing dialog understanding that is
 * hot fixable by regular dev team, instead of by specialized nlu team.
 * The main control that we give regular dev team for control the dialog understanding behavior
 * is the expressions: which describe the mapping from templated surface form text to semantic frame under
 * given context (specified by semantic frame).
 *
 * Instead of keep these in the json, it might be better to keep them as kotlin code, particularly
 * with internal DSL.
 *
 * ExpressionsBuilder is used to build the expressions for one semantic frame. And ExpressionBuilder
 * is used to build one expression that can then be attached to one semantic frame, over and over.
 *
 * The package expression should be integrated into bot scope DuMeta.
 */
data class ExemplarBuilder(val u: String) {
    val content = Json.makeObject()
    init{
        content.put(UTTERANCE, u)
    }

    fun label(l: String) {
        content.put(LABEL, l)
    }

    fun context(f: String, a: String? = null) {
        val context = Json.makeObject()
        context.put(FRAMEID, f)
        if (!a.isNullOrEmpty()) {
            context.put(ATTRIBUTEID, a)
        }
        content.set<JsonObject>(CONTEXT, context)
    }

    fun DontCare() {
        label("DontCare")
    }

    companion object{
        const val LABEL = "label"
        const val UTTERANCE = "utterance"
        const val CONTEXT = "context"
        const val FRAMEID = "frame_id"
        const val ATTRIBUTEID = "attribute_id"
    }
}

class FrameExemplarBuilder (val owner_id: String){
    val expressions = mutableListOf<JsonObject>()
    val subTypes = mutableListOf<String>()

    var recognizer : String? = null
    fun utterance(u: String, init: ExemplarBuilder.() -> Unit = {}) {
        val s = ExemplarBuilder(u)
        s.init()
        expressions.add(s.content)
    }

    fun toJsonObject() : JsonObject {
        val content = Json.makeObject()
        content.put(OWNERID, owner_id)
        content.set<JsonArray>(EXPRESSIONS, Json.makeArray(expressions))
        return content
    }

    companion object{
        const val OWNERID = "owner_id"
        const val EXPRESSIONS = "expressions"

    }
}

class EntityTypeBuilder(val t: String) {
    val recognizers  = mutableListOf<String>()
    val entities = mutableMapOf<String, List<String>>()
    var parent : String? = null
    var children: List<String> = mutableListOf()

    fun parent(p : String) {
        parent = p
    }

    fun children(c: List<String>) {
        children = c
    }

    fun children(vararg c : String) {
        children = c.toList()
    }

    fun entity(u: String, vararg exprs: String) {
        entities[u] = exprs.toList()
    }

    fun recognizer(r: String) {
        recognizers.add(r)
    }

    fun toEntityType() : EntityType {
        return EntityType(t, recognizers, entities, parent, children)
    }
}


/**
 * There are two levels of the information needed by du, some at schema level, like get entities,
 * which should be the same for different language; some at language level, where different language should
 * have different implementation (but it should be singleton).
 *
 */
interface LangPack {
    val frames : List<JsonObject>
    val entityTypes: Map<String, EntityType>
    val frameSlotMetas: Map<String, List<DUSlotMeta>>
    val typeAlias: Map<String, List<String>>

    fun frame(ownerId: String, init: FrameExemplarBuilder.() -> Unit) : JsonObject {
        val p = FrameExemplarBuilder(ownerId)
        p.init()
        return p.toJsonObject()
    }

    fun entityType(type: String, init: EntityTypeBuilder.() -> Unit) : EntityType {
        val p = EntityTypeBuilder(type)
        p.init()
        return p.toEntityType()
    }
}