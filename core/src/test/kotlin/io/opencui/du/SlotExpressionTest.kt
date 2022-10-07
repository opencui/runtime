package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import kotlin.test.assertEquals
import org.junit.Test


class SlotExpressionTest() : DuTestHelper() {

    val expressionJson = IChatbot.parseByFrame("""
     {"agent_id":"test_slot_expression_copy","expressions":[{"owner_id":"lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.ent","expressions":[{"utterance":"from ${'$'}from${'$'}","context":{"frame_id":"lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.something","attribute_id":"from"}},{"utterance":"to ${'$'}to${'$'}","context":{"frame_id":"lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.something","attribute_id":"to"}}]}]}
     """.trimIndent())

    val agent = object: DUMeta {
        override fun getLang(): String { return "en" }
        override fun getLabel(): String { return "Banks" }
        override fun getVersion(): String { return "" }


        override fun getFrameExpressions(): JsonArray {
            return expressionJson
        }

        override fun getEntityMeta(name:String): EntityMeta? {
            return Json.decodeFromString<Map<String, EntityMeta>>(
                """{"lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.ent":{"recognizer":["DucklingRecognizer"]}}""")[name]
        }

        override fun getEntities(): Set<String> {
            return setOf("account_type", "account", "recipient", "date_time", "lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.ent")
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(
                name, when (name) {
                    "account_type" -> "saving's\tsavings\tsaving's\tsaving's account\ncheckings\tchecking\tchecking's\tchecking's account\tthe checking one"
                    "account" -> "\tsavings\n\tflexible"
                    "date_time" -> "today\ttoday\ntomorrow\ttomorrow\nOctober1\tOctober 1\t10-1"
                    else -> ""
                }
            )
        }

        override fun getSlotMetas(frame: String): List<DUSlotMeta> {
            return when(frame) {
                "Banks_1.CheckBalance" -> listOf(
                        DUSlotMeta("Banks_1.account_type",
                                listOf("which account?")),
                        DUSlotMeta("balance",
                                listOf("which account?"), "AmountOfMoney"))
                "Banks_1.TransferMoney" -> listOf(
                        DUSlotMeta("Banks_1.account_type",
                                listOf("which account?")),
                        DUSlotMeta("Banks_1.TransferMoney.amount",
                                listOf("how much?"), "AmountOfMoney"),
                        DUSlotMeta("Banks_1.TransferMoney.recipient_account_name",
                                listOf("to whom?")))
                else -> emptyList()
            }
        }

        override fun isEntity(name: String): Boolean {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun testSurroundingWords() {
        val surroundings = extractSlotSurroundingWords(expressionJson, agent.getEntities().toSet())
        assertEquals(
                setOf("to"),
                surroundings.first["lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.something#to"]!!.toSet(),
                "amount should have prefixes: of, and"
        )
        assertEquals(
                setOf("from"),
                surroundings.first["lib_yige.test_slot_expression_copy.v_bf6ae74df2e52a2ebd2788ba209d994b.something#from"]!!.toSet(),
                "amount should have prefixes: of, and"
        )
    }
}