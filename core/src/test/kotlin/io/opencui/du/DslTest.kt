package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import kotlin.test.assertEquals

import org.junit.Test

object En : LangPack {
    override val frames = listOf(
        frame("Banks_1.TransferMoney") {
            utterance("${'$'}date_time_slot${'$'}")
            utterance("Yes, please make a transfer.")
            utterance("Okay, please make a transfer for me.")
            utterance("Please help me make a money transfer")
            utterance("Okay thats cool please make a fund transfer")
            utterance("Make a transfer of ${'$'}amount${'$'}.") {
                context("Banks_1.TransferMoney")
            }
            utterance("Great, let's make a transfer.")
            utterance("Make a transfer to ${'$'}recipient_account_name${'$'}")
            utterance("I wanna make a transfer")
            utterance("send ${'$'}amount${'$'} and give it to ${'$'}recipient_account_name${'$'} and go with the ${'$'}account_type${'$'} account") {
                context("Banks_1.TransferMoney", "amount")
                label("negation")

            }
            utterance("I'm interested in making a money transfer.")
        },
        frame("io.opencui.core.DontCare") {
            utterance("any recipient") {
                context("account")
            }
            utterance("whatever frame")
        }
    )

    override val entityTypes: Map<String, EntityType> = mapOf(
        "city" to entityType("city") {
            recognizer("ListRecognizer")
            entity("beijing", "bei jing", "shou du")
            entity("label", "expr1", "expr2")
        }
    )

    override val frameSlotMetas: Map<String, List<DUSlotMeta>> = mapOf()
    override val typeAlias: Map<String, List<String>> = mapOf()

    fun Int.getDialogAct(vararg slot: String): String {
        return "En"
    }
}

object Zh : LangPack {
    override val frames = listOf(
        frame("Banks_1.TransferMoney") {
            utterance("${'$'}date_time_slot${'$'}")
            utterance("Yes, please make a transfer.")
            utterance("Okay, please make a transfer for me.")
            utterance("Please help me make a money transfer")
            utterance("Okay thats cool please make a fund transfer")
            utterance("Make a transfer of ${'$'}amount${'$'}.") {
                context("Banks_1.TransferMoney")
            }
            utterance("Great, let's make a transfer.")
            utterance("Make a transfer to ${'$'}recipient_account_name${'$'}")
            utterance("I wanna make a transfer")
            utterance("send ${'$'}amount${'$'} and give it to ${'$'}recipient_account_name${'$'} and go with the ${'$'}account_type${'$'} account") {
                context("Banks_1.TransferMoney", "amount")
                label("negation")

            }
            utterance("I'm interested in making a money transfer.")
        },
        frame("io.opencui.core.DontCare") {
            utterance("any recipient") {
                context("account")
            }
            utterance("whatever frame")
        }
    )

    override val entityTypes: Map<String, EntityType> = mapOf(
        "city" to entityType("city") {
            recognizer("ListRecognizer")
            entity("beijing", "bei jing", "shou du")
            entity("label", "expr1", "expr2")
        }
    )

    override val frameSlotMetas: Map<String, List<DUSlotMeta>> = mapOf()
    override val typeAlias: Map<String, List<String>> = mapOf()

    fun Int.getDialogAct(vararg slot: String): String {
        return "Zh"
    }
}

class DslTest() : DuTestHelper() {

    val agent = object: DUMeta {
        override fun getLang(): String { return "en" }
        override fun getLabel(): String { return "Banks" }


        override fun getFrameExpressions(): JsonArray {
            return Json.makeArray(En.frames)
        }

        override fun getEntityMeta(name: String): EntityMeta? {
            return mapOf<String, EntityMeta>()[name]
        }

        override fun getEntities(): Set<String> {
            return setOf("account_type", "account", "recipient", "date_time")
        }

        fun getEntityContent(name:String): String {
            return when(name) {
                "account_type" -> "saving's\tsavings\tsaving's\tsaving's account\ncheckings\tchecking\tchecking's\tchecking's account\tthe checking one"
                "account" -> "\tsavings\n\tflexible"
                "date_time" -> "today\ttoday\ntomorrow\ttomorrow\nOctober1\tOctober 1\t10-1"
                else -> ""
            }
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(name, getEntityContent(name))
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

    private val normalizers = listOf(ListRecognizer(agent))
    @Test
    fun f() {
        val searcher = ExpressionSearcher(agent)
        val results = searcher.search("please make a transfer.")

        for (result in results) {
            println("${result.probes} by ${result.ownerFrame} with ${result.score}")
        }
        assertEquals(4, results.size)
    }

    @Test
    // Adding special tokens should not affect search result
    fun testSearchWithSpecialTokens() {
        val searcher = ExpressionSearcher(agent)
        val results = searcher.search("please* make? a transfer 02/15.")
        for (result in results) {
            println("${result.probes} by ${result.ownerFrame} with ${result.score}")
        }
        assertEquals(4, results.size)
    }

    @Test
    fun testContextDefinition() {
        with(En) {
            val x : Int = 2
            println(x.getDialogAct())
        }

        with(Zh) {
            val x : Int = 2
            println(x.getDialogAct())
        }
    }

    @Test
    // search with "today" should recall the expression "$date_time_slot$"
    // date_time_slot -> date_time -> expression: "(date_time)", probe: "< date_time_slot >"
    // "today" -> recognized date_time=today -> search with query "today (date_time)"
    fun testSearchWithEntityValue() {
        val searcher = ExpressionSearcher(agent)
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        normalizers.recognizeAll("today", listOf(), emap)
        val results = searcher.search("today", DialogExpectations(), emap)
        //TODO we are change how we match enity only expression. so revisit when done.
        assertEquals(0, results.size)
        //assertEquals("< date_time_slot >", results.first().probes)
    }

    @Test
    fun testContextSearch() {
        val searcher = ExpressionSearcher(agent)
        val results = searcher.search("make a transfer.", DialogExpectations(ExpectedFrame("Banks_1.TransferMoney")))
        assert(results.isNotEmpty())
        assertEquals("""{"frame_id":"Banks_1.TransferMoney"}""", results[0].document.getField("context").stringValue())
    }

    @Test
    fun testGetFields() {
        val doc = Document()
        doc.add(StringField(ScoredDocument.SLOTTYPE, "A", Field.Store.YES))
        doc.add(StringField(ScoredDocument.SLOTTYPE, "B", Field.Store.YES))
        val res = doc.getFields(ScoredDocument.SLOTTYPE)
        println(res.map{it.stringValue()})
        assertEquals(res.size, 2)
    }


    @Test
    fun testLitRecognizer() {
        // Just need to prepare agent.
        val recognizer = ListRecognizer(agent)
        println("types = ${recognizer.typeTable.stringToId}")
        println("labels = ${recognizer.labelTable.stringToId}")
        println("mentions = ${recognizer.mentionTable.stringToId}")
        println("tokens = ${recognizer.tokenTable.stringToId}")

        // 5 types: account_type, account, recipient, date_time
        // account_type: saving's, checkings (3)
        // account: ?, ? (1)
        // recipient: DontCare (2)
        // date_time: today, tomorrow, October1 (4)
        // someFrame: DontCare (2)
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        val utt = "we like to deposit this into your savings account to any recipient, whatever frame"
        recognizer.parse(utt, listOf(), emap)
        println("emap: $emap")

        assert(emap.containsKey("account_type"))
        val annotations = emap["account_type"]
        assertEquals("\"saving's\"", annotations!![0].norm())
        assert(emap.containsKey("account"))
        val annotations1 = emap["account"]
        assertEquals("\"account.0\"", annotations1!![0].norm())

        /* TODO(sean) add the donotcare back.
        val annotations2 = emap["recipient"]
        assertEquals("DontCare", annotations2!![0].norm())
        */

        val utt2 = "I'd like to get the weather for october 1"
        val emap2 = mutableMapOf<String, MutableList<SpanInfo>>()
        recognizer.parse(utt2, listOf(), emap2)
        println("emap2: $emap2")
        assert(emap2.containsKey("date_time"))
        assertEquals("\"October1\"", emap2["date_time"]!![0].norm())

        val utt3 = "I'd like to use account"
        val emap3 = mutableMapOf<String, MutableList<SpanInfo>>()
        recognizer.parse(utt3, listOf(), emap3)
        println("emap3: $emap3")
        // assertEquals("\"_partial_match\"", emap3["account_type"]!![0].norm())
        val eevent = recognizer.findRelatedEntity("account_type", "account")!!
        assertEquals(2, eevent.size)

        val utt4 = "I'd like to the second"
        val emap4 = mutableMapOf<String, MutableList<SpanInfo>>()
        recognizer.parse(utt4, listOf(), emap4)
        assertEquals(0, emap4.size)
    }

    @Test
    fun testBuildExpression() {
        val expression = ExpressionSearcher.buildTypedExpression("The account has \$balance\$ money", "Banks_1.CheckBalance", agent)
        assertEquals("The account has < AmountOfMoney > money", expression)
    }

    @Test
    fun testRegexPattern() {
        val pattern = Regex("\\{.*\\}")
        val value = "{'@class'='io.opencui.core.That'}"
        assert(pattern.matches(value))
    }

    @Test
    fun testBuildUtterance() {
        assertEquals(
                "my phone is ${'$'}PhoneNumber${'$'} and email is ${'$'}EEE${'$'}",
                ExpressionSearcher.toLowerProperly("My Phone is ${'$'}PhoneNumber${'$'} and Email is ${'$'}EEE${'$'}"))
        assertEquals(
                "${'$'}Phone${'$'} is my phone number",
                ExpressionSearcher.toLowerProperly("${'$'}Phone${'$'} Is My Phone Number")
        )
        assertEquals(
                "${'$'}Phone${'$'}${'$'}PPP${'$'} is my phone number",
                ExpressionSearcher.toLowerProperly("${'$'}Phone${'$'}${'$'}PPP${'$'} Is My Phone Number")
        )
    }
}

