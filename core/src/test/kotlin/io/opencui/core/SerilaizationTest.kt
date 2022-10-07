package io.opencui.serialization

import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.test.MoreBasics
import io.opencui.test.PayMethod


import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

class SerializationTest {
    val expressionJson = """
{
  "agent_id": "Banks_1",
  "expressions": [
    {
      "owner_id": "Banks_1.TransferMoney",
      "expressions": [
        {
          "utterance": "${'$'}date_time_slot${'$'}"
        },
        {
          "utterance": "Yes, please make a transfer."
        },
        {
          "utterance": "Okay, please make a transfer for me."
        },
        {
          "utterance": "Please help me make a money transfer"
        },
        {
          "utterance": "Okay thats cool please make a fund transfer"
        },
        {
          "utterance": "Make a transfer of ${'$'}amount${'$'}.",
          "context": {
            "frame_id": "Banks_1.TransferMoney"
          }
        },
        {
          "utterance": "Great, let's make a transfer."
        },
        {
          "utterance": "Make a transfer to ${'$'}recipient_account_name${'$'}"
        },
        {
          "utterance": "I wanna make a transfer"
        },
        {
          "utterance": "send ${'$'}amount${'$'} and give it to ${'$'}recipient_account_name${'$'} and go with the ${'$'}account_type${'$'} account",
          "context": {
            "frame_id": "Banks_1.TransferMoney"
          }
        },
        {
          "utterance": "I'm interested in making a money transfer."
        }
      ]
    },
    {
      "owner_id": "recipient",
      "expressions": [
        {
          "label": "DontCare",
          "utterance": "any recipient"
        }
      ]
    },
    {
      "owner_id": "someFrame",
      "expressions": [
        {
          "label": "DontCare",
          "utterance": "whatever frame"
        }
      ]
    }
  ]
}
""".trimIndent()
    /*
    @Test
    fun testBasics() {
        val botInfo = BotInfo("io", "framely", "zh", "v_3o3iuqpoeiurqpwoeir")
        val sess = SessionInfo(botInfo, "twilio", "4256987018")

        val s = sess.string()
        println(s)
        val nsess = SessionInfo.loads(s)
        assertEquals(sess, nsess)
    }
*/
    @Test
    fun testLocalDate() {
        val date = LocalDateTime.parse("2002-12-30T00:00:00")
        val sdate = Json.encodeToString(date)

        val ndate = Json.decodeFromString<LocalDateTime>(sdate)
        println(ndate)
        assertEquals(date, ndate)

        val dates = listOf(LocalDateTime.parse("2002-12-30T00:00:00"), LocalDateTime.parse("2002-12-31T00:00:00"))
        val sdates = Json.encodeToString(dates)
        println(sdates)
        val ndates = Json.decodeFromString<List<LocalDateTime>>(sdates)
        println(ndates)
        assertEquals(dates, ndates)
    }

    @Test
    fun testPrimitives() {
        val date: Int = 1805
        val sdate = Json.encodeToString(date)
        println(sdate)
        val ndate = Json.decodeFromString<Int>(sdate)
        println(ndate)
        assertEquals(date, ndate)
        val json = Json.parseToJsonElement(sdate)
        println(json::class.java)
    }

    @Test
    fun testBoolean() {
        val date: Boolean = true
        val sdate = Json.encodeToString(date)
        println(sdate)
        val ndate = Json.decodeFromString<Boolean>(sdate)
        println(ndate)
        assertEquals(date, ndate)
    }

    @Test
    fun testString() {
        val date: String = "string is tricky"
        val sdate = Json.encodeToString(date)
        println(sdate)
        val ndate = Json.decodeFromString<String>(sdate)
        println(ndate)
        assertEquals(date, ndate)
    }

    @Test
    fun testExpressions() {

        val expressionRoot = Json.parseToJsonElement(expressionJson)
        val exprOwners = expressionRoot["expressions"]!! as JsonArray
        assertEquals(exprOwners.size(), 3)
        println(exprOwners[0])
        val owner = exprOwners[0]
        val ownerId = ((owner as JsonObject).get("owner_id") as JsonPrimitive).content()
        println(ownerId)
        println(((owner as JsonObject).get("owner_id")))

        val expressions = owner["expressions"]!! as JsonArray
        assertEquals(expressions.size(), 11)

        val expression = expressions[1]
        val exprObject = expression as JsonObject
        val utterance = (exprObject.get("utterance")!! as JsonPrimitive).content().removeSuffix(".")
        println(utterance)



    }

    @Test
    fun testEntity() {
        val p = PayMethod("visa").apply { origValue = "visa card" }
        val serialization = Json.encodeToJsonElement(p)
        assertEquals(TextNode("visa"), serialization)
        val p2: PayMethod = Json.decodeFromJsonElement(TextNode("visa"))
        assertEquals("visa", p2.value)
    }

    @Test
    fun testFrame() {
        val moreBasics: MoreBasics = MoreBasics().apply {
            payMethod = PayMethod("visa")
            int_condition = 1
            bool_condition = true
            conditional_slot = "condition"
        }
        val moreBasicsJson = """{"int_condition":1,"bool_condition":true,"conditional_slot":"condition","payMethod":"visa"}"""
        assertEquals(moreBasicsJson, Json.encodeToString(moreBasics))

        val deserialization: MoreBasics = Json.decodeFromString(moreBasicsJson)
        assertEquals("visa", deserialization.payMethod!!.value)
        assertEquals("condition", deserialization.conditional_slot)

        val jsonNode = Json.parseToJsonElement(moreBasicsJson)
        assertEquals(moreBasicsJson, jsonNode.toString())
        val deserializationFromNode: MoreBasics = Json.decodeFromJsonElement(jsonNode)
        assertEquals("visa", deserializationFromNode.payMethod!!.value)

    }


}
