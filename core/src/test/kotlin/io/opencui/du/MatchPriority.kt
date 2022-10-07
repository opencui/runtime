package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.JsonArray
import org.junit.Test
import kotlin.test.assertEquals

class MatchPriorityTest : DuTestHelper() {
    // fake agent, intent: Greeting has expression "long time no see"
    // someIntent.slot1 (type of entity "any" which has content "long time")
    // when expectation is empty, utterance "long time" some match intent Greeting
    // but when expection is {someIntent, slot1}, utterance "long time" should be recognized as slot value
    val agent = object : DUMeta {
        override fun getLang(): String {
            return "en"
        }

        override fun getLabel(): String {
            return "a"
        }

        override fun getVersion(): String {
            return ""
        }

        override fun getBranch(): String {
            return "master"
        }

        override fun getFrameExpressions(): JsonArray {
            return IChatbot.parseByFrame("""
                    {
                      "agent_id": "a",
                      "expressions": [
                        {
                          "owner_id": "io.opencui.core.Greeting",
                          "expressions": [
                            {"utterance": "long time no see"},
                            {"utterance": "good day"}
                          ]
                        }
                      ]
                    }
                """.trimIndent())
        }

        override fun getEntityMeta(name:String): EntityMeta? {
            return mapOf<String, EntityMeta>()[name]
        }

        override fun getEntities(): Set<String> {
            return setOf("org.a.any")
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(
                name, when (name) {
                    "org.a.any" -> "l1\tlonger time"
                    else -> ""
                }
            )
        }

        override fun getSlotMetas(frame: String): List<DUSlotMeta> {
            return when (frame) {
                "org.a.someIntent" -> listOf(
                        DUSlotMeta("slot1",
                                listOf("some slot"),
                                "org.a.any"),
                        DUSlotMeta("slot2",
                                listOf("some slot"),
                                "kotlin.String")
                )
                else -> emptyList()
            }
        }

        override fun isEntity(name: String): Boolean {
            return when (name) {
                "org.a.any" -> true
                else -> false
            }
        }
    }

    val stateTracker = BertStateTracker(
            agent,
            32,
            3,
            0.5f,
            0.1f,
            0.5f
    )
    @Test
    // TODO check convert result
    fun testMatchPriority() {
        // case 1: no expectation, "long time" should match "long time no see" -> Greeting
        var frameEvent = stateTracker.convert(
                "s",
                "it has been a long time")
        println("frame event without expectation: $frameEvent")
        assertEquals(
                "[FrameEvent(type=Greeting, slots=[], frames=[], packageName=io.opencui.core)]",
                frameEvent.toString(),
                "long time should match Greeting"
        )

        // case 2: expected slot1, "long time" should fill slot via list recognizer
        frameEvent = stateTracker.convert(
                "s",
                "longer time",
                DialogExpectations(ExpectedFrame("org.a.someIntent", "slot1"))
        )
        println("frame event with expectation {someIntent.slot1}: $frameEvent")
        assertEquals(
                """[FrameEvent(type=someIntent, slots=[EntityEvent(value="l1", attribute=slot1)], frames=[], packageName=org.a)]""",
                frameEvent.toString(),
                "long time should fill slot when expected slot type is org.a.any"
        )

        // case 3: expected slot1, but utterance can not fill slot1, should match new frame -> Greeting
        frameEvent = stateTracker.convert(
                "s",
                "good day",
                DialogExpectations(ExpectedFrame("org.a.someIntent", "slot1"))
        )
        println("frame event with expectation {someIntent.slot1}: $frameEvent")
        assertEquals(
                "[FrameEvent(type=Greeting, slots=[], frames=[], packageName=io.opencui.core)]",
                frameEvent.toString(),
                "should match new frame"
        )

        // case 4: expected slot2(kotlin.String), any utterance should fill slot2
        frameEvent = stateTracker.convert(
                "s",
                "good day",
                DialogExpectations(ExpectedFrame("org.a.someIntent", "slot2"))
        )
        println("frame event with expectation {someIntent.slot2}: $frameEvent")
        assertEquals(
                "[FrameEvent(type=someIntent, slots=[EntityEvent(value=good day, attribute=slot2)], frames=[], packageName=org.a)]",
                frameEvent.toString(),
                "should fill slot2 since kotlin.String has highest priority"
        )
    }
}
