package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.serialization.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class RecognizerTest : DuTestHelper() {

    val agent = object: DUMeta {
        override fun getLang(): String { return "en" }
        override fun getLabel(): String { return "Banks" }
        override fun getVersion(): String { return "" }

        override fun getFrameExpressions(): JsonArray {
            return IChatbot.parseByFrame(File("../agents/Banks/expression.json").readText())
        }

        override fun getEntityInstances(name: String): Map<String, List<String>> {
            return IChatbot.parseEntityToMapByNT(
                name, when (name) {
                    else -> ""
                }
            )
        }

        val entityMetas = Json.decodeFromString<Map<String, EntityMeta>>("""{"AmountOfMoney":{"recognizer":[]},
                |"CreditCardNumber":{"recognizer":[]},
                |"Distance":{"recognizer":[]},
                |"Duration":{"recognizer":[]},
                |"io.opencui.core.Email":{"recognizer":["DucklingRecognizer"]},
                |"io.opencui.core.Ordinal":{"recognizer":["DucklingRecognizer"]},
                |"io.opencui.core.PhoneNumber":{"recognizer":["DucklingRecognizer"]},
                |"Quantity":{"recognizer":[]},
                |"Temperature":{"recognizer":[]},
                |"java.time.LocalDateTime":{"recognizer":["DucklingRecognizer"]},
                |"java.time.LocalTime":{"recognizer":["DucklingRecognizer"]},
                |"java.time.LocalDate":{"recognizer":["DucklingRecognizer"]},
                |"java.time.YearMonth":{"recognizer":["DucklingRecognizer"]},
                |"java.time.Year":{"recognizer":["DucklingRecognizer"]},
                |"Url":{"recognizer":[]},
                |"Volume":{"recognizer":[]},
                |"kotlin.Int":{"recognizer":["DucklingRecognizer"]},
                |"kotlin.Float":{"recognizer":["DucklingRecognizer"]},
                |"kotlin.String":{"recognizer":[]},
                |"kotlin.Boolean":{"recognizer":[]},
                |"Person":{"recognizer":[]},
                |"Location":{"recognizer":[]}}""".trimMargin())

        override fun getEntityMeta(name:String): EntityMeta? {
            return entityMetas[name]
        }

        override fun getEntities(): Set<String> {
            return entityMetas.keys
        }

        override fun getSlotMetas(frame: String): List<DUSlotMeta> {
            return when(frame) {
                "Banks_1.CheckBalance" -> listOf(
                        DUSlotMeta("Banks_1.account_type",
                                listOf("which account?")))
                "Banks_1.TransferMoney" -> listOf(
                        DUSlotMeta("Banks_1.account_type",
                                listOf("which account?")),
                        DUSlotMeta("Banks_1.TransferMoney.amount",
                                listOf("how much?")),
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
    fun testListRecZh() {
        val zhAgent = object: DUMeta {
            override fun getLang(): String { return "zh" }
            override fun getLabel(): String { return "Banks" }
            override fun getVersion(): String { return "" }
            override fun getBranch(): String {
                TODO("Not yet implemented")
            }

            override fun getFrameExpressions(): JsonArray {
                return IChatbot.parseByFrame("""{"expressions":[]}""")
            }

            override fun getEntityInstances(name: String): Map<String, List<String>> {
                return IChatbot.parseEntityToMapByNT(
                    name, when (name) {
                        "city" -> "北京1\t北京\t帝都\nshanghai\t上海"
                        else -> ""
                    }
                )
            }

            override fun getEntityMeta(name: String): EntityMeta? {
                return Json.decodeFromString<Map<String, EntityMeta>>("""{"java.time.LocalDateTime":{"recognizer":["DucklingRecognizer"]}}""".trimMargin())[name]
            }

            override fun getEntities(): Set<String> {
                return setOf("city", "java.time.LocalDateTime")
            }

            override fun getSlotMetas(frame: String): List<DUSlotMeta> {
                return listOf()
            }

            override fun isEntity(name: String): Boolean {
                TODO("Not yet implemented")
            }
        }
        val listRecognizer = ListRecognizer(zhAgent)
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        listRecognizer.parse("我想去北京", listOf(), emap)
        assertEquals("\"北京1\"", emap["city"]!![0].norm())

        val ducklingRecognizer = DucklingRecognizer(zhAgent)
        ducklingRecognizer.parse("明天上午九点", listOf(), emap)
        assertTrue { emap.containsKey("java.time.LocalDateTime") }
    }

    val recognizer = DucklingRecognizer(agent)

    @Test
    fun testConvertWithExpectation() {
        val s = "I will leave tomorrow at eight, my email is sean.wu@naturali.io"
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        recognizer.parse(s, listOf(), emap)

        val email = emap["io.opencui.core.Email"]!![0]
        assertEquals("\"sean.wu@naturali.io\"", email.norm()!!)

        emap.clear()
        recognizer.parse("I will leave march 2nd", listOf(), emap)
        println(emap)
        val year = emap["java.time.LocalDate"]!![0]
        println(year.norm())

        emap.clear()
        println("now working with time")
        recognizer.parse("I will leave at 3 in the morning", listOf(), emap)
        println(emap)
        val time = emap["java.time.LocalTime"]!![0].norm()
        assertEquals(time, "\"03:00:00\"")

        emap.clear()
        recognizer.parse("my number is +1(555)234-2343", listOf(), emap)
        assertEquals("\"15552342343\"", emap["io.opencui.core.PhoneNumber"]!![0].norm())

        emap.clear()
        recognizer.parse("how about eighty eight?", listOf(), emap)
        println(emap)
        assertEquals("88", emap["kotlin.Int"]!![0].norm())

        emap.clear()
        recognizer.parse("how about eighty eight point two?", listOf(), emap)
        assertEquals("88.2", emap["kotlin.Float"]!![0].norm())

        emap.clear()
        recognizer.parse("third one please", listOf(), emap)
        assertEquals("\"3\"", emap["io.opencui.core.Ordinal"]!![0].norm())

        emap.clear()
        recognizer.parse("the second one please", listOf("io.opencui.core.Ordinal"), emap)
        assertEquals("\"2\"", emap["io.opencui.core.Ordinal"]!![0].norm())

        emap.clear()
        // We modified the java time LocalDate so it does not take proposition.
        recognizer.parse("I will leave on march 3rd", listOf(), emap)
        val date = emap["java.time.LocalDate"]!![0]
        assertEquals(16, date.start)

        // We modified the java time LocalDate so it does not take proposition.
        emap.clear()
        recognizer.parse("I will leave at 8:00pm", listOf(), emap)
        val dates = emap["java.time.LocalDate"]
        assertEquals(0, dates?.size ?: 0)
    }

    @Test
    fun testZoneId() {
        val ozone = ZoneId.of("PRC")
        println(ozone)
        val szone = Json.encodeToString(ozone)
        println(szone)
        val nzone = Json.decodeFromString<ZoneId>(szone)
        println(nzone)
        assertEquals(ozone, nzone)
    }

}
