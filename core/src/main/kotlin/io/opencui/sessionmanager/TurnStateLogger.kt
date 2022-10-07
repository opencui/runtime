package io.opencui.sessionmanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.concurrent.getOrSet

object TurnStateLogger {
    // ThreadLocal is convenient for collecting states from where session is out of scope, like in closures; but it can be tricky.
    // Alter it when there is a substitute solution
    private val threadLocalState: ThreadLocal<ObjectNode> = ThreadLocal()

    fun getState(): ObjectNode {
        return threadLocalState.getOrSet { ObjectNode(JsonNodeFactory.instance) }
    }

    fun setState(key: String, value: JsonNode) {
        val obj = threadLocalState.getOrSet { ObjectNode(JsonNodeFactory.instance) }
        obj.replace(key, value)
    }

    fun addState(key: String, value: JsonNode) {
        val obj = threadLocalState.getOrSet { ObjectNode(JsonNodeFactory.instance) }
        val arr = obj.get(key) as? ArrayNode ?: ArrayNode(JsonNodeFactory.instance)
        arr.add(value)
        obj.replace(key, arr)
    }

    // must be called before thread is finished if the thread is fetched from a pool
    fun clear() {
        threadLocalState.remove()
    }
}

