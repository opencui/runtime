package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import org.junit.Test
import kotlin.test.assertEquals

class StateTrackerTest : DuTestHelper() {
    val expressionJson = """
{
  "agent_id": "Banks_1",
  "expressions": [
    {
      "owner_id": "org.Banks_1.someFrame",
      "expressions": [
        {
          "label": "DontCare",
          "utterance": "whatever frame"
        }
      ]
    },
    {
      "owner_id": "org.Banks_1.buyTicket",
      "expressions": [
        {
          "utterance": "i want to buy a ticket from ${'$'}departure${'$'} to ${'$'}destination${'$'}"
        },
        {
          "utterance": "my departure is ${'$'}departure${'$'} , please",
          "context": {
            "frame_id": "org.Banks_1.buyTicket",
            "attribute_id": "departure"
          }
        }
      ]
    },
    {
      "owner_id": "org.Banks_1.someIntent",
      "expressions": [
        {
          "utterance": "this is intent",
          "partial_application" : [
                "missSlot"
           ]
        },
        {
          "utterance": "give me some recommendation",
          "label": "Recommendation"
        },
        {
          "utterance": "trigger a function with ${'$'}slot_city${'$'}",
          "function_slot": "fs",
          "context": {
            "frame_id": "org.Banks_1.someIntent"
          }
        }
      ]
    },
    {
      "owner_id": "io.opencui.core.DontCare",
      "expressions": [
        {
          "utterance": "as you like"
        }
      ]
    },
    {
      "owner_id": "io.opencui.core.confirmation.Yes",
      "expressions": [
        {
          "utterance": "confirmed"
        }
      ]
    },
    {
      "owner_id": "io.opencui.core.confirmation.No",
      "expressions": [
        {
          "utterance": "that is incorrect"
        },
        {
          "utterance": "I don't think so"
        }
      ]
    },
    {
          "owner_id": "io.opencui.core.hasMore.No",
          "expressions": [
            {
              "utterance": "nope, thanks"
            },
            {
              "utterance": "I am good now."
            }
          ]
    },
    {
      "owner_id": "io.opencui.core.PageSelectable",
      "expressions": [
        {
          "utterance": "next page",
          "function_slot": "nextPage"
        }
      ]
    }
  ]
}
""".trimIndent()

    val agent = object : DUMeta {
        override fun getLang(): String {
            return "en"
        }

        override fun getLabel(): String {
            return "Banks"
        }

        override fun getVersion(): String {
            return ""
        }

        override fun getFrameExpressions(): JsonArray {
            return IChatbot.parseByFrame(expressionJson)
        }

        override fun getEntityMeta(name:String): EntityMeta? {
            return Json.decodeFromString<Map<String, EntityMeta>> ("""{
                    "io.opencui.core.Ordinal":{"recognizer":["DucklingRecognizer"]}
                  }""".trimMargin())[name]
        }

        override fun getEntities(): Set<String> {
            return setOf("org.Banks_1.account_type", "org.Banks_1.city", "io.opencui.core.Ordinal")
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(
                name, when (name) {
                    "org.Banks_1.account_type" -> "saving's\tsavings\tsaving's\tsaving's account\ncheckings\tchecking\tchecking's\tchecking's account"
                    "org.Banks_1.account" -> "\tsavings\n\tflexible"
                    "org.Banks_1.city" -> "beijing\tbeijing\nchongqing\tchongqing\nshanghai\tshanghai"
                    else -> ""
                }
            )
        }

        override fun getSlotMetas(frame: String): List<DUSlotMeta> {
            return when (frame) {
                "org.Banks_1.someIntent" -> listOf(
                        DUSlotMeta("slot1",
                                listOf("frame slot"),
                                "org.Banks_1.someFrame"),
                        DUSlotMeta("slot2",
                                listOf("hotel"),
                                "org.Banks_1.someEntity"),
                        DUSlotMeta("slot_city",
                                listOf("slot_city"),
                                "org.Banks_1.city"))
                "org.Banks_1.CheckBalance" -> listOf(
                        DUSlotMeta("account_type",
                                listOf("from which account?"),
                                "org.Banks_1.account_type"),
                        DUSlotMeta("recipient_account_type",
                                listOf("to which account?"),
                                "org.Banks_1.account_type"))
                "org.Banks_1.buyTicket" -> listOf(
                        DUSlotMeta("departure",
                                listOf("departure"),
                                "org.Banks_1.city",
                        ).apply{
                            prefixes = setOf("from")
                            suffixes = setOf("to")
                        },
                        DUSlotMeta("destination",
                                listOf("destination"),
                                "org.Banks_1.city",
                        ).apply {
                            prefixes = setOf("to")
                        }
                )
                "framely.core.PageSelectableMulti" -> listOf(
                        DUSlotMeta(
                                "index",
                                listOf("index"),
                                "io.opencui.core.Ordinal",
                                isMultiValue = true,
                        ))
                "framely.core.PageSelectableSingle" -> listOf(
                        DUSlotMeta(
                                "index",
                                listOf("index"),
                                "io.opencui.core.Ordinal",
                                isMultiValue = false,
                        ))
                "io.opencui.core.PagedSelectable" -> listOf(
                        DUSlotMeta(
                                "index",
                                listOf("index"),
                                "io.opencui.core.Ordinal",
                                isMultiValue = false,
                        ))
                else -> emptyList()
            }
        }

        override fun isEntity(name: String): Boolean {
            return when (name) {
                "org.Banks_1.someEntity" -> true
                "io.opencui.core.Ordinal" -> true
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
    fun testConvertWithExpectation() {
        val frameEvents = stateTracker.convert(
                "s",
                "savings",
                DialogExpectations(ExpectedFrame("org.Banks_1.CheckBalance", "account_type")))
        println("frame events: $frameEvents")
        assertEquals(1, frameEvents.size)
        assertEquals("CheckBalance", frameEvents[0].type)
        assertEquals(1, frameEvents[0].slots.size)
        assertEquals("account_type", frameEvents[0].slots[0].attribute)
        assertEquals("\"saving's\"", frameEvents[0].slots[0].value)
        assertEquals("savings", frameEvents[0].slots[0].origValue)

        val frameEvents2 = stateTracker.convert(
                "s",
                "savings",
                DialogExpectations(ExpectedFrame("org.Banks_1.CheckBalance", "recipient_account_type")))
        println("frame events: $frameEvents2")
        assertEquals(1, frameEvents2.size)
        assertEquals("CheckBalance", frameEvents2[0].type)
        assertEquals(1, frameEvents2[0].slots.size) // TODO
        assertEquals("recipient_account_type", frameEvents2[0].slots[0].attribute)
        assertEquals("\"saving's\"", frameEvents2[0].slots[0].value)
        assertEquals("savings", frameEvents2[0].slots[0].origValue)
    }

    @Test
    fun testMatchIntent() {
        val frameEvents = stateTracker.convert("s", "this is intent")
        println("frame events: $frameEvents")
        assertEquals(1, frameEvents.size)
        assertEquals("someIntent", frameEvents[0].type)
        assertEquals("org.Banks_1", frameEvents[0].packageName)
        assertEquals(1, frameEvents[0].slots.size)
    }

    @Test
    fun testMultiValue() {
        // framely.core.PageSelectableMulti # index is a slot which allows multi_value
        // framely.core.PageSelectableSingle # index is a same slot except not allow multi_value
        val frameEvent = stateTracker.convert(
                "s",
                "first and fourth",
                DialogExpectations(ExpectedFrame("framely.core.PageSelectableMulti", "index"))
        )
        println("multi value event: $frameEvent")
        assertEquals("[FrameEvent(type=PageSelectableMulti, " +
                "slots=[EntityEvent(value=\"1\", attribute=index), " +
                "EntityEvent(value=\"4\", attribute=index)], frames=[], packageName=framely.core)]",
                frameEvent.toString(),
                "should fill index with 1 and 4(multi value)"
        )

        val frameEvent2 = stateTracker.convert(
                "s",
                "first and fourth",
                DialogExpectations(ExpectedFrame("framely.core.PageSelectableSingle", "index"))
        )
        println("single value event: $frameEvent2")
        assertEquals("[FrameEvent(type=PageSelectableSingle, " +
                "slots=[EntityEvent(value=\"1\", attribute=index)], frames=[], packageName=framely.core)]",
                frameEvent2.toString(),
                "should fill index with 1(multi value not allowed)"
        )
    }

    @Test
    fun testMatchIDontThinkSo() {
        val frameEvents = stateTracker.convert(
                "s",
                "I don't think so",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2"), ExpectedFrame("io.opencui.core.Confirmation")))
        println("frame events: $frameEvents")
        assertEquals(
                "[FrameEvent(type=No, slots=[], frames=[], packageName=io.opencui.core.confirmation)]",
                frameEvents.toString(),
                "should match \"I don't think so\""
        )
    }

    @Test
    fun testDontCareExpected() {
        val expects = listOf(
                ExpectedFrame("io.opencui.core.HasMore", "status"),
                ExpectedFrame("LibraryIntegration.ScheduleApp.scheduleIntent", "extraRequirement"),
                ExpectedFrame("io.opencui.core.PagedSelectable", "index"))

        val frameEvents = stateTracker.convert(
                "s",
                "as you like",
                DialogExpectations(DialogExpectation(expects))
        )

        println(frameEvents.toString())
        assertEquals(frameEvents[0].slots[0].attribute, "index")
        assertEquals(frameEvents[0].slots[0].value, "\"1\"")
    }

    @Test
    fun testDontCareProactive() {
        // TODO(sean): need to revisit this when we have multiple intention support.
        val frameEvents = stateTracker.convert("s", "this is intent, whatever frame")
        println("frame events: $frameEvents")
        // assertEquals(1, frameEvents.size)
        // assertEquals("someFrame", frameEvents[0].type)
        // assertEquals("org.Banks_1", frameEvents[0].packageName)
        // TODO (sean, add this back when we support proactive dontcare again.
        // assertEquals(1, frameEvents[0].slots.size)
        // assertEquals("slot1", frameEvents[0].slots[0].attribute)
        // assertEquals("{}", frameEvents[0].slots[0].value)
    }

    // @Test
    fun testDontCareReactive1() {
        //  TODO: figure out what is the right DnotCare for frame slot, turn on test.
        val frameEvents = stateTracker.convert(
                "s",
                "as you like",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot1", allowDontCare = true))
        )
        println("frame events 1: $frameEvents")
        assertEquals(1, frameEvents.size)
        assertEquals("someIntent", frameEvents[0].type)
        assertEquals("org.Banks_1", frameEvents[0].packageName)
        assertEquals(1, frameEvents[0].slots.size)
        assertEquals("slot1", frameEvents[0].slots[0].attribute)
        assertEquals("\"_DontCare\"", frameEvents[0].slots[0].value)
    }

    @Test
    fun testDontCareReactive2() {
        // test dont care on entity slot
        val frameEvents2 = stateTracker.convert(
                "s",
                "as you like",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2", allowDontCare = true))
        )
        println("frame events 2: $frameEvents2")
        assertEquals(1, frameEvents2.size)
        assertEquals("someIntent", frameEvents2[0].type)
        assertEquals("org.Banks_1", frameEvents2[0].packageName)
        assertEquals(1, frameEvents2[0].slots.size)
        assertEquals("slot2", frameEvents2[0].slots[0].attribute)
        assertEquals("\"_DontCare\"", frameEvents2[0].slots[0].value)
    }

    @Test
    fun testConfirmation() {
        val frameEvents = stateTracker.convert(
                "s",
                "confirmed",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2"), ExpectedFrame("io.opencui.core.Confirmation")))
        assertEquals("[FrameEvent(type=Yes, slots=[], frames=[], packageName=io.opencui.core.confirmation)]",
                frameEvents.toString())

        // current state is not confirming, should not return frame events of confirmation
        // TODO(@flora) in this case, we should try to match the second best expression?
        val frameEvents2 = stateTracker.convert(
                "s",
                "confirmed",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2")))

        //assertEquals("[FrameEvent(type=IDonotGetIt, slots=[], frames=[], packageName=io.opencui.core)]",
        //        frameEvents2.toString())
        assertEquals("[FrameEvent(type=IDonotGetIt, slots=[], frames=[], packageName=io.opencui.core)]", frameEvents2.toString())

        // confirming, utterance is not yes/no, should return nothing
        val frameEvents3 = stateTracker.convert(
                "s",
                "i want change my hotel",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2")))
        println("confirm event3: $frameEvents3")
        //assertEquals("[FrameEvent(type=IDonotGetIt, slots=[], frames=[], packageName=io.opencui.core)]",
        //        frameEvents2.toString())
        assertEquals("[FrameEvent(type=IDonotGetIt, slots=[], frames=[], packageName=io.opencui.core)]", frameEvents2.toString())
    }

    @Test
    fun testHasMore() {
        val frameEvents = stateTracker.convert(
                "s",
                "I am good now",
                DialogExpectations(ExpectedFrame("org.Banks_1.someIntent", "slot2"), ExpectedFrame("io.opencui.core.HasMore")))
        assertEquals("[FrameEvent(type=No, slots=[], frames=[], packageName=io.opencui.core.hasMore)]",
                frameEvents.toString())
    }


    @Test
    fun testRecommendationExpression() {
        val frameEvents = stateTracker.convert(
                "s",
                "give me some recommendation",
                DialogExpectations(ExpectedFrame("any.intent"))
        )
        println("frame event: $frameEvents")
        /* TODO(sean.wu) revisit this when we figure out how to do recommendation.
        assertEquals(
                "[FrameEvent(type=someIntent.Recommendation, slots=[], frames=[], packageName=org.Banks_1)]",
                frameEvents.toString(),
                "weak recommendation expression test failed")
         */
    }


    @Test
    fun testPartialApplication() {
        val frameEvents = stateTracker.convert("s", "this is intent")
        println("frame event: $frameEvents")
        assertEquals(
                "[FrameEvent(type=someIntent, slots=[EntityEvent(value=\"_context\", attribute=missSlot)], frames=[], packageName=org.Banks_1)]",
                frameEvents.toString(),
                "weak recommendation expression test failed")
    }


    @Test
    fun testMultiFrameMatching() {
        // state tracker should try to fill slot for active frames
        val frameEvent = stateTracker.convert(
                "s",
                "go to beijing",
                DialogExpectations(ExpectedFrame("framely.core.PageSelectable"), ExpectedFrame("org.Banks_1.someIntent"))
        )
        println(frameEvent.toString())
        assertEquals("[FrameEvent(type=someIntent, " +
                "slots=[EntityEvent(value=\"beijing\", attribute=slot_city)], frames=[], packageName=org.Banks_1)]",
                frameEvent.toString(),
                "testMultiFrameMatching failed"
        )
    }

    @Test
    fun testExtractValue() {
        val startLogits = mutableListOf<List<Float>>()
        val endLogits = mutableListOf<List<Float>>()
        // slot: from
        startLogits.add(listOf(2.196567E-5f, 2.6365742E-5f, 3.1778778E-5f, 1.3596944E-4f, 0.4008711f, 3.536189E-4f, 3.245814E-5f, 0.5985267f))
        endLogits.add(listOf(1.825438E-6f, 1.8325056E-6f, 7.967485E-6f, 1.12943E-5f, 4.643959E-4f, 0.11388547f, 6.501569E-6f, 0.8856207f))
        // slot: to
        startLogits.add(listOf(9.678948E-6f, 1.5287478E-5f, 1.6331456E-5f, 7.200543E-5f, 0.2823309f, 2.5269715E-4f, 1.6962993E-5f, 0.7172861f))
        endLogits.add(listOf(1.469735E-6f, 1.6144468E-6f, 5.9993013E-6f, 7.2088346E-6f, 3.1644202E-4f, 0.13221303f, 4.2701727E-6f, 0.8674499f))

        val originalInput = "buy a ticket from beijing city to shanghai"
        val recognizedEntities = mutableMapOf<String, List<SpanInfo>>()
        recognizedEntities["city"] = listOf(SpanInfo("city", 18, 25, false, value = "beijing", score = 2.0f),
                SpanInfo("city", 34, 42, false, value = "shanghai", score = 2.0f))
        val slotMap = mapOf(
                "from" to DUSlotMeta("from", listOf("from"), "city"),
                "to" to DUSlotMeta("to", listOf("to"), "city"))
        val classLogits = listOf(0.11279419f, 0.8870314f, 1.743299E-4f, 0.03408716f, 0.96576923f, 1.4359347E-4f)
        val prediction = UnifiedModelResult(
                listOf("buy", "a", "ticket", "from", "beijing", "city", "to", "shanghai"),
                classLogits,
                startLogits,
                endLogits,
                listOf<Long>(0, 4, 6, 13, 18, 26, 31, 34),
                listOf<Long>(3, 5, 12, 17, 25, 30, 33, 42)
        )

        // utterance: "buy a ticket from beijing city to shanghai"
        // expected slot: null
        // should fill both from and to: {from: beijing, to: shanghai}
        assertEquals(
                "{=[EntityEvent(value=shanghai, attribute=to), EntityEvent(value=beijing, attribute=from)]}",
                stateTracker.extractSlotValues(
                        DUContext("", originalInput).apply {putAll(recognizedEntities)},
                        "",
                        slotMap,
                        prediction).toString(),
                "test failed when expected slot is empty")


        // utterance: "beijing"
        // expected slot: "from"
        // should fill "from"
        val prediction2 = UnifiedModelResult(
                listOf("beijing"),
                classLogits,
                listOf(listOf(0.1f), listOf(0.1f)),
                listOf(listOf(0.1f), listOf(0.1f)),
                listOf(0),
                listOf(1))
        val emap2 = mapOf("city" to listOf(SpanInfo("city", 0, 7, false)))
        assertEquals(
                "{=[EntityEvent(value=beijing, attribute=from)]}",
                stateTracker.extractSlotValues(
                        DUContext("", "beijing").apply{ putAll(emap2) },
                        "from",
                        slotMap,
                        prediction2).toString(),
                "should fill expected slot:from")

        // utterance: "beijing"
        // expected slot: "to"
        // should fill "to"
        assertEquals(
                "{=[EntityEvent(value=beijing, attribute=to)]}",
                stateTracker.extractSlotValues(
                        DUContext( "","beijing").apply {putAll(emap2)},
                        "to",
                        slotMap,
                        prediction2).toString(),
                "should fill expected slot:to")
    }

    @Test
    fun testPrefixSuffixBoost() {
        val originalInput = "Alice want to buy a ticket from shanghai to beijing at today"
        // test low level function: extractValues
        val recognizedEntities = mutableMapOf<String, List<SpanInfo>>()
        recognizedEntities["city"] = listOf(SpanInfo("city", 44, 51, false, value = "beijing", score = 2.0f),
                SpanInfo("city", 32, 40, false, value = "shanghai", score = 2.0f))
        val slotMap = mapOf(
                "from" to DUSlotMeta("from", listOf("departure"),
                        "city").apply{ prefixes = mutableSetOf("from"); suffixes = mutableSetOf("to")},
                "to" to DUSlotMeta("to", listOf("destination"),
                        "city").apply { prefixes = mutableSetOf("to") }
        )
        val prediction = UnifiedModelResult(
                listOf(originalInput),
                (1..(3 * slotMap.size)).map { 0f },
                (1..(slotMap.size)).map { listOf(0f) },
                (1..(slotMap.size)).map { listOf(0f) },
                listOf(),
                listOf())

        // utterance: "Alice want to buy a ticket from shanghai to beijing at today"
        // expected slot: null
        // with prefix suffix bonus, shanghai should fill from, beijing to
        // should fill both from and to: {from: shanghai, to: beijing}
        assertEquals(
                "{=[EntityEvent(value=shanghai, attribute=from), EntityEvent(value=beijing, attribute=to)]}",
                stateTracker.extractSlotValues(
                        DUContext("", originalInput).apply{putAll(recognizedEntities)},
                        "",
                        slotMap,
                        prediction).toString(),
                "from should be shanghai and to should be beijing")
    }

    @Test
    fun testPrefixSuffixBoostFull() {
        val originalInput = "Alice want to buy a ticket from shanghai to beijing at today"

        // test high level function: convert
        val frameEvent = stateTracker.convert("s", originalInput)
        println("frame event: $frameEvent")
        assertEquals(
                "[FrameEvent(type=buyTicket, " +
                        "slots=[EntityEvent(value=\"beijing\", attribute=destination), EntityEvent(value=\"shanghai\", attribute=departure)], " +
                        "frames=[], packageName=org.Banks_1)]",
                frameEvent.toString(),
                "from should be shanghai and to should be beijing"
        )
    }
    @Test
    fun testTwoSlotsWithSameEntity() {
        val frameEvent = stateTracker.convert(
                "s",
                "march chongqing",
                DialogExpectations(ExpectedFrame("org.Banks_1.buyTicket", "departure"))
        )
        println("frame event: $frameEvent")
        // should fill "chongqing" to departure
        assertEquals(
                "[FrameEvent(type=buyTicket, slots=[EntityEvent(value=\"chongqing\", attribute=departure)], frames=[], packageName=org.Banks_1)]",
                frameEvent.toString()
        )
    }

    @Test
    fun testSingleTokenSlotFilling() {
        val frameEvent = stateTracker.convert(
                "s",
                "march",
                DialogExpectations(ExpectedFrame("org.Banks_1.buyTicket", "departure"))
        )
        // should not fill "march" to departure (we skip slot model for single token utterance)
        assertEquals(
                "[FrameEvent(type=IDonotGetIt, slots=[], frames=[], packageName=io.opencui.core)]",
                frameEvent.toString()
        )
    }

    @Test
    fun testReplaceEntityValues() {
        val replaced = stateTracker.replaceEntityValues(
                "零一二三四五六七八九十",
                mutableMapOf(
                    "奇数" to mutableListOf(
                            SpanInfo("奇数", 1, 2, false),
                            SpanInfo("奇数", 5, 6, false)
                    ),
                    "偶数" to mutableListOf(
                            SpanInfo("偶数", 0, 1, false)
                    )),
                setOf("奇数","偶数" ))
        println("replaced: $replaced")
        assertEquals(replaced.size, 6)
        assertEquals(replaced, listOf(
                "零< 奇数 >二三四五六七八九十",  // replaced 1 range
                "零一二三四< 奇数 >六七八九十",
                "< 偶数 >一二三四五六七八九十",
                "零< 奇数 >二三四< 奇数 >六七八九十", // replaced 2 ranges
                "< 偶数 >< 奇数 >二三四五六七八九十",
                "< 偶数 >一二三四< 奇数 >六七八九十"))
    }

    @Test
    fun testIntentModel() {
        val utterance = "我的手机号是 123"
        val probes = listOf("我的手机号是 <phonenumber>", "我的手机号是 [MASK]")
        val yProbs = stateTracker.nluModel.predictIntent("zh", utterance, probes)
        println("intent model resp: $yProbs")
    }

    @Test
    fun testIntentModel1() {
        val utterance = "i am good now"
        val probes = listOf("i am good now", "i am good now.", "i am good now?")
        val yProbs = stateTracker.nluModel.predictIntent("en", utterance, probes)
        println("intent model resp: $yProbs")
    }

    @Test
    fun testSlotModel() {
        val utterance = "march"
        val probes = listOf("departure?")
        val predictions = stateTracker.nluModel.predictSlot("en", utterance, probes)
        print("request slot model, utterance: $utterance, probes: $probes")

        println("slot model result \n" +
                "class_logits: ${predictions.classLogits}\n" +
                "segments: $predictions.segments\n" +
                "start_logits: ${predictions.startLogitss}\n" +
                "end_logits: ${predictions.endLogitss}")

        for ((index, slot) in probes.withIndex()) {
            if (index >= predictions.startLogitss.size) continue
            if (predictions.classLogits[index * 3 + 1] > stateTracker.slot_threshold) {
                val span = stateTracker.extractValue(
                        utterance,
                        DUSlotMeta("slot"),
                        predictions.get(index)
                )
                if (span != null) {
                    for (s in span) {
                        println("predicted slot: $slot, ${s.value}, score: ${s.score}")
                    }
                }
            } else if (predictions.classLogits[index * 3 + 2] > stateTracker.slot_threshold) {
                // DontCare.
                println("predicted slot: $slot as DontCare")
            }
        }
    }
}
