package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.JsonArray
import kotlin.test.assertEquals


import org.junit.Test


class ZhSearchTest() : DuTestHelper() {
    val test = """{$}"""
    val expressionJson = """{"agent_id":"CarFinanceSimple","expressions":[{"owner_id":"io.opencui.core.CleanSession","expressions":[{"utterance":"结束当前对话"}]},{"owner_id":"io.opencui.core.DontCare","expressions":[{"utterance":"随便"},{"utterance":"都行"},{"utterance":"都一样"}]},{"owner_id":"io.opencui.core.confirmation.Yes","expressions":[{"utterance":"是的"},{"utterance":"确认"},{"utterance":"当然"},{"utterance":"yes"},{"utterance":"确定"},{"utterance":"好的"},{"utterance":"可以"},{"utterance":"是的","context":{"frame_id":"Regression.CarFinanceSimple.v_04a227a81a6b025230d9ac2882267507.EarlyTerminationImpl","attribute_id":"date"}}]},{"owner_id":"io.opencui.core.confirmation.No","expressions":[{"utterance":"不"},{"utterance":"不是"},{"utterance":"no"},{"utterance":"不好"},{"utterance":"不行"},{"utterance":"还不行"},{"utterance":"我不确定"},{"utterance":"算了","context":{"frame_id":"Regression.CarFinanceSimple.v_04a227a81a6b025230d9ac2882267507.EarlyTerminationImpl","attribute_id":"date"}}]},{"owner_id":"io.opencui.core.hasMore.No","expressions":[{"utterance":"没有了"},{"utterance":"没有了谢谢"},{"utterance":"我好了"},{"utterance":"就这些了"}]},{"owner_id":"Regression.CarFinanceSimple.v_04a227a81a6b025230d9ac2882267507.Greeting","expressions":[{"utterance":"你好"},{"utterance":"您好"}]},{"owner_id":"io.opencui.core.PagedSelectable","expressions":[{"utterance":"下一页","context":{"frame_id":"io.opencui.core.PagedSelectable"}},{"utterance":"翻页","context":{"frame_id":"io.opencui.core.PagedSelectable"}},{"utterance":"还有别的吗？","context":{"frame_id":"io.opencui.core.PagedSelectable"}},{"utterance":"都不太喜欢","context":{"frame_id":"io.opencui.core.PagedSelectable"}},{"utterance":"上一页","context":{"frame_id":"io.opencui.core.PagedSelectable"}}]},{"owner_id":"Regression.CarFinanceSimple.v_04a227a81a6b025230d9ac2882267507.HowMuchMoneyDoIOwn","expressions":[{"utterance":"我还有多少钱没有还？"},{"utterance":"我还欠多少钱?"},{"utterance":"还有多少钱没还"}]},{"owner_id":"Regression.CarFinanceSimple.v_04a227a81a6b025230d9ac2882267507.EarlyTermination","expressions":[{"utterance":"我想提前还款"},{"utterance":"提前结清"}]},{"owner_id":"io.opencui.core.AbortIntent","expressions":[{"utterance":"我不要了"},{"utterance":"我不想了"}]}]}""".trimIndent()

    val agent = object: DUMeta {
        override fun getLang(): String { return "zh" }
        override fun getLabel(): String { return "CarFinanceSimple" }
        override fun getVersion(): String { return "v_04a227a81a6b025230d9ac2882267507" }

        override fun getFrameExpressions(): JsonArray {
            return IChatbot.parseByFrame(expressionJson)
        }

        override fun getEntityMeta(name:String): EntityMeta? {
            return mapOf<String, EntityMeta>()[name]
        }

        override fun getEntities(): Set<String> {
            return setOf("account_type", "account", "recipient","date_time")
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(
                name, when (name) {
                    else -> ""
                }
            )
        }

        override fun getSlotMetas(frame: String): List<DUSlotMeta> {
            return when(frame) {
                else -> emptyList()
            }
        }

        override fun isEntity(name: String): Boolean {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun confirmationWithoutExpectation() {
        val searcher = ExpressionSearcher(agent)
        val results = searcher.search("是的")

        for (result in results) {
            println("${result.probes} by ${result.ownerFrame} with ${result.score}")
        }
        assertEquals(0, results.size)

        val results0 = searcher.search("随便")

        for (result in results0) {
            println("${result.probes} by ${result.ownerFrame} with ${result.score}")
        }
        assertEquals(1, results0.size)
    }

    @Test
    fun confirmationWithExpectation() {
        val searcher = ExpressionSearcher(agent)
        val results = searcher.search("是的", DialogExpectations(ExpectedFrame("io.opencui.core.Confirmation")))

        for (result in results) {
            println("${result.probes} by ${result.ownerFrame} with ${result.score}")
        }
        assertEquals(3, results.size)
    }

}

