package io.opencui.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.node.ValueNode
import io.opencui.serialization.*
import java.io.Serializable


// This is used for reference.
// Reference can be used to find the referent in the context.
interface Reference
data class That(val slot: String? = null): Reference {}
data class ListThat(val index: Int, val slot: String? = null): Reference {}
data class ContentThat<T>(val value: T): Reference {}


/**
 * This is used to keep the input from user. But it can be produced by some rules.
 * In general, builder can not define the label that starts with _, or they are {} wrapped.
 * NOTE:
 * value == "" means that "does not care"
 * value == "{}" means that we need to find actual value from contextual referent, there are different references.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntityEvent(
    val value: String,
    val attribute: String): Serializable {
    var type: String? = null
    var isUsed: Boolean = false
    var origValue: String? = null
    
    var isLeaf: Boolean = true

    // TODO(sean) what is this used for?
    val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    val isDontCare: Boolean
        get() = value == ""


    // We assume the value is encoding of the reference.
    val isReference: Boolean
        get() = pattern.matches(value)

    companion object {
        val pattern = Regex("\\{.*\\}")
    }
}

enum class EventSource {
    USER,
    UNKNOWN
}

/**
 * This is used for specify proposed template match, each is contains one trigger, and
 * multiple slot filling.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FrameEvent(
        var type: String,
        val slots: List<EntityEvent> = emptyList(),
        val frames: List<FrameEvent> = emptyList(),
        var packageName: String? = null): Serializable {
    var attribute: String? = null
    var query: String? = null
    var triggered: Boolean = false
    var inferredFrom: Boolean = false
    var refocused: Boolean = false
    var typeUsed: Boolean = false
    val isUsed: Boolean
        get() = typeUsed || slots.firstOrNull { it.isUsed } != null || frames.firstOrNull { it.isUsed } != null
    val consumed : Boolean
        get() = slots.firstOrNull { !it.isUsed } == null && frames.firstOrNull { !it.usedUp } == null
    val usedUp: Boolean
        get() = ((slots.isNotEmpty() || frames.isNotEmpty()) && consumed) || (slots.isEmpty() && frames.isEmpty() && typeUsed)
    val activeSlots: List<EntityEvent>
        get() = slots.filter {!it.isUsed }

    var dontCare : Boolean = false

    val fullType: String
        get() = "${packageName}.$type"

    @JsonIgnore
    val triggerParameters: MutableList<Any?> = mutableListOf()
    @JsonIgnore
    var slotAssignments: MutableMap<String, ()->Any?> = mutableMapOf()

    var turnId : Int = -1

    fun updateTurnId(pturnId: Int) {
        turnId = pturnId
    }

    var source: EventSource = EventSource.UNKNOWN

    companion object {
        // TODO(xiaobo): is this frameName simple or qualified?
        fun fromJson(frameName: String, jsonElement: JsonElement): FrameEvent {
            val slotEvents = mutableListOf<EntityEvent>()
            if (jsonElement is JsonObject) {
                for ((k, v) in jsonElement.fields()) {
                    if (k == "@class") continue
                    if (v is ValueNode && v !is JsonNull) {
                        slotEvents += EntityEvent(v.toString(), k)
                    } else if (v is ArrayNode) {
                        assert(v.size() == 2)
                        slotEvents += EntityEvent((v[1] as ValueNode).toString(), k).apply {
                            type = (v[0] as TextNode).textValue()
                        }
                    }
                }
            }
            return FrameEvent(frameName, slotEvents)
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParsedQuery(
    val query: String,
    var frames: List<FrameEvent>
) {
    init {
        for (event in frames) {
            event.query = query
        }
    }
}

