package io.opencui.core

import com.fasterxml.jackson.databind.node.NullNode
import io.opencui.core.da.ComponentDialogAct
import io.opencui.core.user.UserInfo
import io.opencui.du.DucklingRecognizer
import io.opencui.du.TfRestBertNLUModel
import io.opencui.serialization.*
import io.opencui.sessionmanager.*
import org.junit.Test
import java.io.*
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class TableInfo(
    val createSql: String,
    val csvFile: String,
    val tableName: String) {
    val eraseSql: String? = "drop table if exists $tableName"
}

class RuntimeTest {
    val dm = DialogManager()
    val botInfo = BotInfo("io.opencui", "test", "en", "master")
    val inMemorySessionStore = InMemorySessionStore()
    val versionStore = InMemoryBotStore().apply { putVersion(botInfo, "test") }
    val sessionManager = SessionManager(inMemorySessionStore)

    val databaseConfig = mapOf(
        "mobile" to Pair("jdbc:h2:./h2db/mobile;DATABASE_TO_UPPER=FALSE", listOf(
            TableInfo(
                """
                    CREATE TABLE mobile (
                    id INT PRIMARY KEY,
                    cellphone VARCHAR(255),
                    amount double DEFAULT NULL)
                    """,
                "classpath:/test/mobile.csv", "mobile"
            ),
            TableInfo(
                """
                    CREATE TABLE cellphone (
                    id INT PRIMARY KEY,
                    cellphoneMapping VARCHAR(255),
                    nameMapping VARCHAR(255))
                    """,
                "test/cellphone.csv", "cellphone"
            )
        )),
        "vacation" to Pair("jdbc:h2:./h2db/vacation;DATABASE_TO_UPPER=FALSE", listOf(
            TableInfo(
                """
                    CREATE TABLE flight (
                    id INT PRIMARY KEY,
                    origin VARCHAR(255),
                    destination VARCHAR(255),
                    flight VARCHAR(255))
                    """,
                "classpath:/test/flight.csv", "flight"),
            TableInfo("""
                           CREATE TABLE booked_flight (
                            id INT PRIMARY KEY AUTO_INCREMENT,
                            flight VARCHAR(255),
                            user VARCHAR(255))
                        """.trimIndent(), "", "booked_flight"),
            TableInfo(
                """
                    CREATE TABLE hotel (
                    id INT PRIMARY KEY,
                    city VARCHAR(255),
                    hotel VARCHAR(255))
                    """,
                "classpath:/test/hotel.csv", "hotel"),
            TableInfo("""
                           CREATE TABLE booked_hotel (
                            id INT PRIMARY KEY,
                            hotel VARCHAR(255),
                            user VARCHAR(255))
                        """.trimIndent(), "", "booked_hotel"),
            TableInfo("""
                           CREATE TABLE hotel_address (
                            id INT PRIMARY KEY,
                            hotel VARCHAR(255),
                            address VARCHAR(255))
                        """.trimIndent(), "classpath:/test/hotel_address.csv", "hotel_address")

        )),
        "intent" to Pair("jdbc:h2:./h2db/intents;DATABASE_TO_UPPER=FALSE", listOf(
            TableInfo(
                """
                CREATE TABLE intents (
                id INT PRIMARY KEY,
                node_state VARCHAR(255),
                "@class" VARCHAR(255), 
                current_node NVARCHAR(255) NULL)
                """,
                "classpath:/test/intents.csv", "intents")
        )),
        "dish" to Pair("jdbc:h2:./h2db/dish;DATABASE_TO_UPPER=FALSE", listOf(
            TableInfo(
                """
                CREATE TABLE dish (
                id INT PRIMARY KEY,
                dish VARCHAR(255))
                """,
                "classpath:/test/dish.csv", "dish")
        ))
    )

    init {
        RuntimeConfig.put<String>(DucklingRecognizer::class, "http://172.11.51.61:8000/parse")
        RuntimeConfig.put(TfRestBertNLUModel::class, Triple("172.11.51.61", 8501, null))
        RuntimeConfig.put(ChatbotLoader::class, versionStore)
        Dispatcher.sessionManager = sessionManager

        // init in-memory database for test
        Class.forName("org.h2.Driver")
        for (database in databaseConfig) {
            val dbConn = DriverManager.getConnection(database.value.first, "", "")
            val stmt = dbConn.createStatement()
            for (t in database.value.second) {
                if (t.eraseSql != null) {
                    println(t.eraseSql)
                    stmt.execute(t.eraseSql)
                }

                val sourceDataPath = javaClass.classLoader.getResource(t.csvFile)?.toString() ?: t.csvFile
                val fullSql = t.createSql + if (t.csvFile.isEmpty()) "" else """ AS SELECT * FROM CSVREAD('${sourceDataPath}', null, 'lineComment=#')"""
                println(fullSql)
                try {
                    stmt.execute(fullSql)
                } catch (e : SQLException) {
                    e.printStackTrace()
                    throw RuntimeException("can not execute $fullSql")
                }
            }
        }
    }

    @Test
    fun testBasics() {
        val coreExpected = listOf(
            """>{"query": "3", "frames": [{"type": "Hi", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"What is your name?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Person","slot":"name"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "Person", "slots": [{"value" : "\"Joe\"", "type" : "String", "attribute" : "name"}]}]}""",
            """<{"type":"SlotAskAction","payload":"How old are you?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Person","slot":"age"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "person", "slots": [{"value" : "24", "type" : "Int", "attribute" : "age"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"How old are you?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Person","slot":"age"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "Person", "slots": [{"value" : "24", "type" : "Int", "attribute" : "age"}]}, {"type": "Person", "slots": [{"value": "66", "type": "Int", "attribute": "weight"}]}]}""",
            """<{"type":"SlotAskAction","payload":"How tall are you?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Person","slot":"height"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "Person", "slots": [{"value": "175", "type": "Int", "attribute": "height"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What symptom do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hi","slot":"symptom"}],"status":"OPEN"}""",
            """>{"query": "9", "frames": [{"type": "Person", "slots": [{"value" : "25", "type" : "Int", "attribute" : "age"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What symptom do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hi","slot":"symptom"}],"status":"OPEN"}""",
            """>{"query": "10", "frames": [{"type": "fever", "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What symptom do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hi","slot":"symptom"}],"status":"OPEN"}""",
            """>{"query": "11", "frames": [{"type": "Fever"}]}""",
            """<{"type":"SlotAskAction","payload":"How long did it last?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Fever","slot":"duration"}],"status":"OPEN"}""",
            """>{"query": "13", "frames": [{"type": "Fever", "slots": [{"value" : "4", "type" : "Int", "attribute" : "duration"}]}]}""",
            """<{"type":"SlotAskAction","payload":"what is the cause for it?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Fever","slot":"cause"}],"status":"OPEN"}""",
            """>{"query": "14", "frames": [{"type": "Fever", "slots": [{"value" : "\"I eat something bad.\"", "type" : "String", "attribute" : "cause"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What is your temperature?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Fever","slot":"degree"}],"status":"OPEN"}""",
            """>{"query": "15", "frames": [{"type": "Fever", "slots": [{"value" : "39", "type" : "Int", "attribute" : "degree"}]}]}""",
            """<{"type":"HiAction_0","payload":"Hi, Joe, 175cm, 66kg I know you are 25 year old. But how are you?"}""",
            """<null""",
            """>{"query": "16", "frames": [{"type": "PreDiagnosis", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What pay method do you perfer?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.PreDiagnosis","slot":"method"}],"status":"OPEN"}""",
            """>{"query": "17", "frames": [{"type": "PreDiagnosis", "slots": [{"value" : "\"wrong_method\"", "origValue": "orig_value_visa",  "type" : "PayMethod", "attribute" : "method"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What kind of headache do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Headache"}],"status":"OPEN"}""",
            """>{"query": "18", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.hasMore"}, {"type": "Headache", "slots": [{"value" : "4", "type" : "Int", "attribute" : "duration"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What is the cause for it?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Headache","slot":"cause"}],"status":"OPEN"}""",
            """>{"query": "19", "frames": [{"type": "PreDiagnosis", "slots": [{"value" : "\"visa\"", "origValue": "orig_value_visa",  "type" : "PayMethod", "attribute" : "method"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What is the cause for it?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Headache","slot":"cause"}],"status":"OPEN"}""",
            """>{"query": "20", "frames": [{"type": "Headache", "slots": [{"value" : "\"I bashed my head against a wall.\"", "type" : "String", "attribute" : "cause"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"which part of head?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Headache","slot":"part"}],"status":"OPEN"}""",
            """>{"query": "21", "frames": [{"type": "Headache", "slots": [{"value" : "\"forehead\"", "type" : "String", "attribute" : "part"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What kind of headache do you still have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Headache"}],"status":"OPEN"}""",
            """>{"query": "22", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SlotAskAction","payload":"What symptom do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.PreDiagnosis","slot":"symptoms"}],"status":"OPEN"}""",
            """>{"query": "23", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.hasMore"}, {"type": "Fever", "slots": [{"value" : "4", "type" : "Int", "attribute" : "duration"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"what is the cause for it?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Fever","slot":"cause"}],"status":"OPEN"}""",
            """>{"query": "24", "frames": [{"type": "Fever", "slots": [{"value" : "\"I eat something bad.\"", "type" : "String", "attribute" : "cause"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What is your temperature?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Fever","slot":"degree"}],"status":"OPEN"}""",
            """>{"query": "25", "frames": [{"type": "Fever", "slots": [{"value" : "40", "type" : "Int", "attribute" : "degree"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What symptom do you still have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.PreDiagnosis","slot":"symptoms"}],"status":"OPEN"}""",
            """>{"query": "26", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SlotAskAction","payload":"What id do you have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.PreDiagnosis","slot":"indexes"}],"status":"OPEN"}""",
            """>{"query": "27", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.hasMore"}, {"type": "PreDiagnosis", "slots": [{"value" : "4", "type" : "Int", "attribute" : "indexes"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"What id do you still have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.PreDiagnosis","slot":"indexes"}],"status":"OPEN"}""",
            """>{"query": "28", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SlotAskAction","payload":"what is the id?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.PreDiagnosis","slot":"indexes"}],"status":"OPEN"}""",
            """>{"query": "29", "frames": [{"type": "PreDiagnosis", "slots": [{"value" : "\"visa\"", "origValue": "orig_value_visa",  "type" : "PayMethod", "attribute" : "method"}]}]}""",
            """<{"type":"SlotAskAction","payload":"what is the id?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.PreDiagnosis","slot":"indexes"}],"status":"OPEN"}""",
            """>{"query": "30", "frames": [{"type": "PreDiagnosis", "slots": [{"value" : "5", "type" : "Int", "attribute" : "indexes"}]}]}""",
            """<{"type":"SlotAskAction","payload":"What id do you still have?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.PreDiagnosis","slot":"indexes"}],"status":"OPEN"}""",
            """>{"query": "31", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"PreDiagnosisAction","payload":"Hi, Joe, I know you have 2 ids. Am I right?"}""",
            """<null""",
            """>{"query": "32", "frames": [{"type": "Hello", "slots": [{"attribute": "payable", "value": "\"_context\""}]}]}""",
            """<{"type":"SlotAskAction","payload":"What is your name (MobileWithAdvances)?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.MobileWithAdvances","slot":"name"}],"status":"OPEN"}""",
            """>{"query": "33", "frames": [{"type": "MobileWithAdvances", "slots": [{"value" : "\"David\"", "type" : "String", "attribute" : "name"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MobileWithAdvances, slot : "}]}""",
            """<{"type":"SlotAskAction","payload":"What is your cell number?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Mobile","slot":"cellphone"}],"status":"OPEN"}""",
            """>{"query": "35", "frames": [{"type": "Mobile", "slots": [{"value" : "\"fever\"", "type" : "String", "attribute" : "cellphone"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"your cellphone number is not correct, let's try it again."},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.Mobile&slot=cellphone"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"What is your cell number?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Mobile","slot":"cellphone"}],"status":"OPEN"}""",
            """>{"query": "36", "frames": [{"type": "Mobile", "slots": [{"value" : "\"13800138007\"", "type" : "String", "attribute" : "cellphone"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What much do you want?\nWe have following 2 choices: (7, 955.51), (8, 866.5)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Mobile","slot":"amount"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "37", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Mobile, slot : "}]}""",
            """<{"type":"HelloAction","payload":"So you want to use visa to pay. Am I right?"}""",
            """<null""",
        )
        process(coreExpected)
    }

    @Test
    fun testCompositeSkill() {
        val compositeSkillTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "BookVacation", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"From where?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookFlight","slot":"origin"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "BookFlight", "slots": [{"value": "\"Baltimore\"", "attribute": "origin"}, {"value": "\"Seattle\"", "attribute": "destination"}, {"value": "\"20200301\"", "attribute": "depart_date"}, {"value": "\"20200303\"", "attribute": "return_date"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.BookFlight, slot : flight"}]}""",
            """<{"type":"TextOutputAction","payload":"flight CA1001 is booked for you"}""",
            """<{"type":"BookFlightAction_0","payload":"Flight CA1001 has been successfully booked for you, depart date is 20200301"}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.BookHotel, slot : checkin_date"}""",
            """<{"type":"SlotAskAction","payload":"When to checkout?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookHotel","slot":"checkout_date"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "BookHotel", "slots": [{"value" : "\"20200305\"", "attribute" : "checkout_date"}]}, {"type": "Hotel", "slots": [{"value" : "\"Seattle\"", "attribute" : "city"}]}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.HotelSuggestionIntent, slot : result"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"Which hotel?\nWe have following 2 choices: (Seattle A Hotel), (Seattle B Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hotel","slot":"hotel"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "NextPage", "slots": [],"packageName": "io.opencui.core"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"}]}""",
            """<{"type":"SlotAskAction","payload":"Which hotel?\nWe have following 1 choices: (Seattle C Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hotel","slot":"hotel"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Hotel, slot : hotel"}]}""",
            """<{"type":"SlotAskAction","payload":"ask placeholder"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookHotel","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "9", "frames": [{"type": "HotelAddress", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"Which city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hotel","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "10", "frames": [{"type": "Hotel", "slots": [{"value" : "\"Seattle\"", "attribute" : "city"}, {"value" : "\"Seattle C Hotel\"", "attribute" : "hotel"}]}]}""",
            """<{"type":"SlotAskAction","payload":"unimportant?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.HotelAddress","slot":"unimportant"}],"status":"OPEN"}""",
            """>{"query": "11", "frames": [{"type": "CreateUnimportant", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.HotelAddress, slot : unimportant"}]}""",
            """<{"type":"HotelAddressAction_0","payload":"Address of Seattle C Hotel is Seattle C Street"}""",
            """<{"type":"SlotAskAction","payload":"ask placeholder"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookHotel","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "12", "frames": [{"type": "BookHotel", "slots": [{"value" : "\"holder\"", "attribute" : "placeHolder"}]}]}""",
            """<{"type":"SlotAskAction","payload":"Are u sure of the hotel booking? hotel Seattle C Hotel"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.BookHotel"}],"status":"OPEN"}""",
            """>{"query": "13", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"BookHotelAction_0","payload":"Hotel Seattle C Hotel has been successfully booked for you, checkin date is 20200301"}""",
            """<null""",
        )

        process(compositeSkillTestCase)
    }

    @Test
    fun testEmptySkill() {
        val emptySkillTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "IDonotGetIt", "packageName": "io.opencui.core", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"I did not get that."}""",
            """<null""",
        )
        process(emptySkillTestCase)
    }

    @Test
    fun testIIntent() {
        val iintentTestcase = listOf(
            """>{"query": "1", "frames": [{"type": "CompositeWithIIntent", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"What do you want to do?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.CompositeWithIIntent","slot":"skill"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "HotelAddress", "slots": [{"value" : "\"unimportant_value\"", "type" : "String", "attribute" : "unimportant"}], "packageName": "io.opencui.test"}, {"type": "Hotel", "slots": [{"value" : "\"Seattle\"", "type" : "String", "attribute" : "city"}, {"value" : "\"Seattle A Hotel\"", "type" : "String", "attribute" : "hotel"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"HotelAddressAction_0","payload":"Address of Seattle A Hotel is Seattle Main Street"}""",
            """<null""",
        )
        process(iintentTestcase)
    }

    @Test
    fun testIntentAction() {
        val intentActionTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "FirstLevelQuestion", "slots": [{"value": "true", "attribute": "need_hotel"}]}]}""",
            """<{"type":"IntentAction","payload":"INTENT ACTION : io.opencui.test.BookHotel"}""",
            """<{"type":"SlotAskAction","payload":"When to checkin?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookHotel","slot":"checkin_date"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "BookHotel", "slots": [{"value": "\"20200301\"", "attribute": "checkin_date"}, {"value" : "\"20200305\"", "attribute" : "checkout_date"}, {"value" : "\"holder\"", "attribute" : "placeHolder"}]}, {"type": "Hotel", "slots": [{"value" : "\"Seattle\"", "attribute" : "city"}, {"value": "\"Seattle A Hotel\"", "attribute": "hotel"}]}]}""",
            """<{"type":"SlotAskAction","payload":"Are u sure of the hotel booking? hotel Seattle A Hotel"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.BookHotel"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"BookHotelAction_0","payload":"Hotel Seattle A Hotel has been successfully booked for you, checkin date is 20200301"}""",
            """<null""",
        )
        process(intentActionTestCase)
    }

    @Test
    fun testMoreBasics() {
        val moreBasicsTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "MoreBasics", "slots": [{"value": "4", "attribute": "int_condition"}, {"value": "true", "attribute": "bool_condition"}, {"value" : "\"visa\"", "origValue": "orig_value_visa",  "type" : "PayMethod", "attribute" : "payMethod"}]}]}""",
            """<{"type":"SlotAskAction","payload":"condition met and ask conditional slot"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.MoreBasics","slot":"conditional_slot"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "MoreBasics", "slots": [{"value": "\"right\"", "attribute": "conditional_slot"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MoreBasics, slot : associateSlot"}""",
            """<{"type":"MoreBasics_0","payload":"original value is orig_value_visa, associate slot value is associate value"}""",
            """<null""",
        )
        process(moreBasicsTestCase)
    }

    @Test
    fun testConfirmedSlot() {
        val confirmedSlotTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "IntentNeedConfirm", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"intVar?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.IntentNeedConfirm","slot":"intVar"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "4", "attribute": "intVar"}, {"value": "true", "attribute": "boolVar"}, {"value": "\"str\"", "attribute": "stringVal"}]}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of bool value true"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of the frame values 4 true str"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "No", "slots": []}]}""",
            """<{"type":"SeqAction","payload":[{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.IntentNeedConfirm&slot="},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"intVar?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.IntentNeedConfirm","slot":"intVar"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "4", "attribute": "intVar"}, {"value": "true", "attribute": "boolVar"}, {"value": "\"str\"", "attribute": "stringVal"}]}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of bool value true"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of the frame values 4 true str"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"IntentNeedConfirm_0","payload":"intVal is 4"}""",
            """<null""",
        )
        process(confirmedSlotTestCase)
    }

    @Test
    fun testConfirmedSlot2() {
        val confirmedSlotTestCase2 = listOf(
            """>{"query": "1", "frames": [{"type": "IntentNeedConfirm", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"intVar?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.IntentNeedConfirm","slot":"intVar"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "4", "attribute": "intVar"}, {"value": "\"str\"", "attribute": "stringVal"}]}]}""",
            """<{"type":"SlotAskAction","payload":"boolVar?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "false", "attribute": "boolVar"}]}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of bool value false"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "No", "slots": []}]}""",
            """<{"type":"SeqAction","payload":[{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.IntentNeedConfirm&slot=boolVar"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"boolVar?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "true", "attribute": "boolVar"}]}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of bool value true"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm","slot":"boolVar"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of the frame values 4 true str"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "IntentNeedConfirm", "slots": [{"value": "5", "attribute": "intVar"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of the frame values 5 true str"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.IntentNeedConfirm"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"IntentNeedConfirm_0","payload":"intVal is 5"}""",
            """<null""",
        )
        process(confirmedSlotTestCase2)
    }

    @Test
    fun testIntentSuggestion() {
        val intentSuggestionTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"Which package?\nWe have following 2 choices for intents : (io.opencui.test, BookHotel), (io.opencui.core, IDonotKnowWhatToDo)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.IntentSuggestion","slot":"intentPackage"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.IntentSuggestion, slot : "}]}""",
            """<{"type":"IntentAction","payload":"INTENT ACTION : io.opencui.core.IDonotKnowWhatToDo"}""",
            """<{"type":"TextOutputAction","payload":"I do not know what to do now."}""",
            """<null""",
            """>{"query": "3", "frames": [{"type": "IntentSuggestion", "slots": [], "packageName": "io.opencui.core"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"Which package?\nWe have following 2 choices for intents : (io.opencui.test, BookHotel), (io.opencui.core, IDonotKnowWhatToDo)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.IntentSuggestion","slot":"intentPackage"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "IDonotKnowWhatToDo", "slots": [], "packageName": "io.opencui.core"}]}""",
            """<{"type":"TextOutputAction","payload":"I do not know what to do now."}""",
            """<null""",
        )
        process(intentSuggestionTestCase)
    }

    @Test
    fun testSideIntent() {
        val sideIntentTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "ContractBasedIntentA", "slots": [{"value" : "\"aaa\"", "attribute" : "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"contractId?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ContractBasedIntentA","slot":"contractId"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "CleanSession", "packageName": "io.opencui.core", "slots": []}]}""",
            """<{"type":"CloseSession","payload":"CLEAN SESSION"}""",
            """<null""",
            """>{"query": "3", "frames": [{"type": "ContractBasedIntentA", "slots": [{"value" : "\"AAA\"", "attribute" : "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"contractId?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ContractBasedIntentA","slot":"contractId"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "IDonotGetIt", "packageName": "io.opencui.core", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"I did not get that."}""",
            """<{"type":"SlotAskAction","payload":"contractId?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ContractBasedIntentA","slot":"contractId"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "ContractBasedIntentA", "slots": [{"value" : "\"CONTRACT_123\"", "attribute" : "contractId"}]}]}""",
            """<{"type":"ContractBasedIntentA_0","payload":"Hi, a=AAA; contractId=CONTRACT_123"}""",
            """<null""",
        )
        process(sideIntentTestCase)
    }

    @Test
    fun testRecover() {
        // TODO(sean): we should set up the test correctly so that we do not need to add
        // if (target.channelType != null && target.channelLabel != null)  to closeSession.
        val recoverTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "RecoverTestIntent", "slots": [{"value" : "\"aaa\"", "attribute" : "aaa"}]}]}""",
            """<{"type":"Exception","payload":""}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.RecoverTestIntent","slot":"bbb"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "CleanSession", "packageName": "io.opencui.core", "slots": []}]}""",
            """<{"type":"CloseSession","payload":"CLEAN SESSION"}""",
            """<null""",
            """>{"query": "3", "frames": [{"type": "RecoverTestIntent", "slots": [{"value" : "\"a\"", "attribute" : "aaa"}]}]}""",
            """<{"type":"RecoverTestIntent_0","payload":"Hi, aaa=a; bbb=null"}""",
            """<null""",
        )
        process(recoverTestCase)
    }

    @Test
    fun testAssociation() {
        val assocationTestCase = listOf(
            """>{"query": "1", "frames": [{"type": "AssociationTestIntent", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.AssociationTestIntent, slot : aaa"}""",
            """<{"type":"SlotAskAction","payload":"intent bbb?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.AssociationTestIntent","slot":"bbb"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "AssociationTestIntent", "slots": [{"value" : "\"eee\"", "attribute" : "bbb"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.AssociationTestFrame, slot : "}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.AssociationTestFrame, slot : "}""",
            """<{"type":"AssociationTestIntent_0","payload":"Hi, aaa=aaa; bbb=eee; Aaaa=aaa; Abbb=bbb; Baaa=ccc; Bbbb=ddd"}""",
            """<null""",
        )
        process(assocationTestCase)
    }

    @Test
    fun testValueRecInteractionPrompt() {
        val valueRecInteractionPromptTest = listOf(
            """>{"query": "1", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "2", "attribute" : "a"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 3 choices: (1; true), (2; true), (3; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"3\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecInteractionFrame, slot : "}]}""",
            """<{"type":"ValueRecInteractionIntent_0","payload":"Hi, a=2; b=3; c=true"}""",
            """<null""",
            """>{"query": "3", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "0", "attribute" : "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"b?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "1", "attribute" : "a"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 1 choices: (1; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "ValueRecInteractionFrame", "slots": [{"value" : "1", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecInteractionFrame, slot : "}]}""",
            """<{"type":"ValueRecInteractionIntent_0","payload":"Hi, a=1; b=1; c=true"}""",
            """<null""",
        )
        process(valueRecInteractionPromptTest)
    }

    @Test
    fun testValueRecInteractionSelection() {
        val valueRecInteractionSelectionTest = listOf(
            """>{"query": "1", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "2", "attribute" : "a"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 3 choices: (1; true), (2; true), (3; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "ValueRecInteractionFrame", "slots": [{"value" : "2", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecInteractionFrame, slot : "}]}""",
            """<{"type":"ValueRecInteractionIntent_0","payload":"Hi, a=2; b=2; c=true"}""",
            """<null""",
        )
        process(valueRecInteractionSelectionTest)
    }

    @Test
    fun testValueRecInteractionBypass() {
        val valueRecInteractionBypassTest = listOf(
            """>{"query": "1", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "0", "attribute" : "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"b?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "ValueRecInteractionIntent", "slots": [{"value" : "2", "attribute" : "a"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 3 choices: (1; true), (2; true), (3; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "ValueRecInteractionFrame", "slots": [{"value" : "true", "attribute" : "c"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 3 choices: (1; true), (2; true), (3; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "ValueRecInteractionFrame", "slots": [{"value" : "4", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecInteractionFrame, slot : "}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"b=4; c=true; value check failed"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueRecInteractionFrame&slot="},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 3 choices: (1; true), (2; true), (3; true)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecInteractionFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "ValueRecInteractionFrame", "slots": [{"value" : "2", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecInteractionFrame, slot : "}]}""",
            """<{"type":"ValueRecInteractionIntent_0","payload":"Hi, a=2; b=2; c=true"}""",
            """<null""",
        )

        process(valueRecInteractionBypassTest)
    }

    @Test
    fun testAbortIntent() {
        val abortIntentTest = listOf(
            """>{"query": "1", "frames": [{"type": "Main", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"Good day!"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "BookVacation", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"SlotAskAction","payload":"From where?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookFlight","slot":"origin"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "AbortIntent", "slots": [], "packageName": "io.opencui.core"}]}""",
            """<{"type":"AbortIntentAction","payload":"io.opencui.test.BookVacation is Aborted successfully!"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What else can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "BookVacation", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"SlotAskAction","payload":"From where?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookFlight","slot":"origin"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "AbortIntent", "slots": [{"value": "\"io.opencui.test.Main\"", "attribute": "intentType"}], "packageName": "io.opencui.core"}]}""",
            """<{"type":"AbortIntentAction","payload":"io.opencui.test.Main is Aborted successfully!"}""",
            """<null""",
        )

        process(abortIntentTest)
    }

    @Test
    fun testMultiValueEntityRecIntent() {
        val multiValueEntityRecTest = listOf(
            """>{"query": "1", "frames": [{"type": "MultiValueEntityRecIntent", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"payMethod?\n\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueEntityRecIntent","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"payMethod?\n\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.MultiValueEntityRecIntent","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueEntityRecIntent, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"anything else?\n\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueEntityRecIntent","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueEntityRecIntent, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"anything else?\n\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueEntityRecIntent","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"MultiValueEntityRecIntent_0","payload":"Hi, size = 2"}""",
            """<null""",
    )
        process(multiValueEntityRecTest)
    }

    @Test
    fun testMultiValueFrameRecIntent() {
        val multiValueFrameRecTest = listOf(
            """>{"query": "1", "frames": [{"type": "MultiValueFrameRecIntent", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"hotel?\n\nWe have following 2 choices: (Seattle A Hotel), (Shanghai B Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Hotel"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueFrameRecIntent, slot : hotels"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"any hotel else?\n\nWe have following 2 choices: (Seattle A Hotel), (Shanghai B Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Hotel"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Hotel", "slots": [{"value" : "\"Shanghai\"", "attribute" : "city"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueFrameRecIntent, slot : hotels"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"any hotel else?\n\nWe have following 2 choices: (Seattle A Hotel), (Shanghai B Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Hotel"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"MultiValueFrameRecIntent_0","payload":"Hi, size = 2"}""",
            """<null""",
        )
        process(multiValueFrameRecTest)
    }

    @Test
    fun testCustomizedRecommendation() {
        val customizedRecommendation = listOf(
            """>{"query": "1", "frames": [{"type": "CustomizedRecommendationIntent", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"Which city?\nWe have following 2 choices: (Wuhan; Wuhan D Hotel), (Shanghai; Shanghai B Hotel)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.Hotel","slot":"city"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Hotel, slot : "}]}""",
            """<{"type":"CustomizedRecommendation_0","payload":"Hi, city=Wuhan; hotel=Wuhan D Hotel"}""",
            """<null""",
        )
        process(customizedRecommendation)
    }

    @Test
    fun testValueRecForIIntent() {
        val valueRecForIIntent = listOf(
            """>{"query": "1", "frames": [{"type": "InternalNodeIntent", "slots": [{"value": "\"root\"", "attribute": "current"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (InternalNodeIntent)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.InternalNodeIntent","slot":"skill"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.InternalNodeIntent, slot : skill"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (InternalNodeIntent)\nWe have following 2 choices: (hello), (bye~)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.InternalNodeIntent","slot":"skill"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.InternalNodeIntent, slot : skill"}]}""",
            """<{"type":"TextOutputAction","payload":"Good day!"}""",
            """<null""",
        )
        process(valueRecForIIntent)
    }

    @Test
    fun testIntentSuggestionForMain() {
        val intentSuggestionForMain = listOf(
            """>{"query": "1", "frames": [{"type": "Main", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"Good day!"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "InternalNodeIntent", "slots": [{"value" : "\"hr\"", "attribute" : "current"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (InternalNodeIntent)\nWe have following 2 choices: (bye~), (hello)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.InternalNodeIntent","slot":"skill"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Goodbye", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.InternalNodeIntent, slot : skill"}]}""",
            """<{"type":"TextOutputAction","payload":"Have a nice day! "}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What else can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "Greeting", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"TextOutputAction","payload":"Good day!"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What else can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
    )

        process(intentSuggestionForMain)
    }

    @Test
    fun testEntityRecSelection() {
        val entityRecSelection = listOf(
            """>{"query": "1", "frames": [{"type": "EntityRecSelection", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"payMethod?\n\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.EntityRecSelection","slot":"payMethod"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "EntityRecSelection", "slots": [{"value" : "\"visa\"", "attribute" : "payMethod"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.EntityRecSelection, slot : payMethod"}]}""",
            """<{"type":"EntityRecSelection_0","payload":"Hi, pay method = visa"}""",
            """<null""",
        )
        process(entityRecSelection)
    }

    @Test
    fun testShowOnceRecommendation() {
        // showOnce is not enabled for now
        val showOnceRecommendation = listOf(
            """>{"query": "1", "frames": [{"type": "ShowOnceRecommendation", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"payMethod?\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ShowOnceRecommendation","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ShowOnceRecommendation, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"any payMethod else?\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ShowOnceRecommendation","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "ShowOnceRecommendation", "slots": [{"value" : "\"mastercard\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ShowOnceRecommendation, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"any payMethod else?\nWe have following 3 choices for PayMethod : visa_norm, mastercard_norm, paypal_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ShowOnceRecommendation","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"ShowOnceRecommendation_0","payload":"Hi, pay method = 2"}""",
            """<null""",
        )
        process(showOnceRecommendation)
    }

    @Test
    fun testMultiValueValueCheck() {
        val multiValueValueCheck = listOf(
            """>{"query": "1", "frames": [{"type": "MultiValueValueCheck", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"payMethod?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueValueCheck","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "MultiValueValueCheck", "slots": [{"value" : "\"mastercard\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any payMethod else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueValueCheck","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"payMethodList check failed, size = 1"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.MultiValueValueCheck&slot=payMethodList"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"payMethod?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueValueCheck","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "MultiValueValueCheck", "slots": [{"value" : "\"visa\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any payMethod else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueValueCheck","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"MultiValueValueCheck_0","payload":"Hi, pay method = 1"}""",
            """<null""",
        )
        process(multiValueValueCheck)
    }

    @Test
    fun testSepConfirmExplicit() {
        // TODO: (xiaobo), why we are executing the original confirmation after we have implicit confirmation?
        val sepConfirmation = listOf(
            """>{"query": "1", "frames": [{"type": "SepTestIntentExplicit", "slots": [{"value" : "true", "attribute" : "a"}]}, {"type": "SepTestIntentExplicit", "slots": [{"value" : "\"ccc\"", "attribute" : "c"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"b ?\nsep confirmation b=2, contextC=ccc"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SepTestIntentExplicit","slot":"b"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SepTestIntentExplicit, slot : b"}]}""",
            """<{"type":"SlotAskAction","payload":"original confirmation b=2"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SepTestIntentExplicit","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SepTestIntent_0","payload":"Hi, a=true, b=2, c=ccc"}""",
            """<null""",
        )
        process(sepConfirmation)
    }

    @Test
    fun testSepConfirmImplicit() {
        val sepConfirmation = listOf(
            """>{"query": "1", "frames": [{"type": "SepTestIntentImplicit", "slots": [{"value" : "true", "attribute" : "a"}]}, {"type": "SepTestIntentImplicit", "slots": [{"value" : "\"ccc\"", "attribute" : "c"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"b ?\nsep confirmation b=2, contextC=ccc"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SepTestIntentImplicit","slot":"b"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SepTestIntentImplicit, slot : b"}]}""",
            """<{"type":"SlotAskAction","payload":"original confirmation b=2"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SepTestIntentImplicit","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"SepTestIntentImplicit_0","payload":"Hi, a=true, b=2, c=ccc"}""",
            """<null""",
        )
        process(sepConfirmation)
    }

    @Test
    fun testMinMaxCheck() {
        val minMaxValueCheck = listOf(
            """>{"query": "1", "frames": [{"type": "MultiValueMinMax", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"payMethod?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueMinMax","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "No", "slots": []}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"size = 0 less than 1"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.HasMore&slot=status"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"payMethod?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueMinMax","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "MultiValueMinMax", "slots": [{"value" : "\"mastercard\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"anything else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueMinMax","slot":"payMethodList"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "MultiValueMinMax", "slots": [{"value" : "\"visa\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}, {"type": "MultiValueMinMax", "slots": [{"value" : "\"paypal\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"size = 3 greater than 2"},{"type":"MaxDiscardAction","payload":"DISCARD mv entries that exceed max number, from 3 entries to 2 entries"}]}""",
            """<{"type":"MultiValueMinMax_0","payload":"Hi, size = 2"}""",
            """<null""",
        )
        process(minMaxValueCheck)
    }

    @Test
    fun testMinMaxSep() {
        val minMaxSep = listOf(
            """>{"query": "1", "frames": [{"type": "MultiValueMinMaxWithRec", "slots": []}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"TextOutputAction","payload":"chose pay method visa_norm for you"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueMinMaxWithRec, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"TextOutputAction","payload":"chose pay method visa_norm for you"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.MultiValueMinMaxWithRec, slot : payMethodList"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"anything else?\nWe have following 1 choices for PayMethod : visa_norm."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MultiValueMinMaxWithRec","slot":"payMethodList"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"MultiValueMinMaxWithRec_0","payload":"Hi, size = 2"}""",
            """<null""",
        )
        process(minMaxSep)
    }

    @Test
    fun testValueCheckSwitch() {
        val valueCheckSwitchTest = listOf(
            """>{"query": "1", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "true", "attribute": "b"}]}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "1", "attribute": "a"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"no such a = 1"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueCheckSwitchTest&slot=a"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "3", "attribute": "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"c?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"c"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "2", "attribute": "a"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"no such combination of a = 2 b = true"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueCheckSwitchTest&slot=a, target=io.opencui.test.ValueCheckSwitchTest&slot=b"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "3", "attribute": "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"b?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "false", "attribute": "b"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"no such combination of a = 3 b = false"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueCheckSwitchTest&slot=a, target=io.opencui.test.ValueCheckSwitchTest&slot=b"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "4", "attribute": "a"}]}]}""",
            """<{"type":"SlotAskAction","payload":"b?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"b"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "true", "attribute": "b"}]}]}""",
            """<{"type":"SlotAskAction","payload":"c?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueCheckSwitchTest","slot":"c"}],"status":"OPEN"}""",
            """>{"query": "9", "frames": [{"type": "ValueCheckSwitchTest", "slots": [{"value": "\"ccc\"", "attribute": "c"}]}]}""",
            """<{"type":"ValueCheckSwitchTest_0","payload":"Hi, a = 4 b = true c = ccc"}""",
            """<null""",
        )
        process(valueCheckSwitchTest)
    }

    @Test
    fun testValueClarification() {
        val valueClarificationTest = listOf(
            """>{"query": "1", "frames": [{"type": "Main", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"Good day!"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "BookTrain", "slots": [{"value": "\"Beijing\"", "attribute": "departure"}, {"value": "\"Shenzhen\"", "attribute": "arrival"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"SlotAskAction","payload":"placeHolder?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookTrain","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "WeatherConsult", "slots": [{"value": "\"_context\"", "attribute": "city"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby weather_city_alias, which do you mean: (Beijing_norm), (Shenzhen_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.test.City","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.WeatherConsult, slot : city"}""",
            """<{"type":"WeatherConsult_0","payload":"Hi, city = Beijing weather = cloudy"}""",
            """<{"type":"SlotAskAction","payload":"placeHolder?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookTrain","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "BookTrain", "slots": [{"value": "\"placeholder\"", "attribute": "placeHolder"}]}]}""",
            """<{"type":"BookTrain_0","payload":"Hi, departure = Beijing arrival = Shenzhen"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What else can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "WeatherConsult", "slots": [{"value": "\"_context\"", "attribute": "city"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.Main, slot : skills"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby weather_city_alias, which do you mean: (Beijing_norm), (Shenzhen_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.test.City","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "ValueClarification", "slots": [{"value" : "\"Shenzhen\"", "attribute" : "target"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.WeatherConsult, slot : city"}""",
            """<{"type":"WeatherConsult_0","payload":"Hi, city = Shenzhen weather = sunny"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What else can I do for you? (Main)\nWe have following 2 choices: (internal alias), (internal alias)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.Main","slot":"skills"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
        )
        process(valueClarificationTest)
    }

    @Test
    fun testMultiValueValueClarification() {
        val multiValueValueClarificationTest = listOf(
            """>{"query": "1", "frames": [{"type": "CollectCities", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.CollectCities","slot":"cities"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "CollectCities", "slots": [{"value": "\"Beijing\"", "attribute": "cities"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any city else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.CollectCities","slot":"cities"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "CollectCities", "slots": [{"value": "\"Shenzhen\"", "attribute": "cities"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any city else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.CollectCities","slot":"cities"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "CollectCities", "slots": [{"value": "\"Chengdu\"", "attribute": "cities"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any city else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.CollectCities","slot":"cities"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"CollectCities_0","payload":"Hi, cities size = 3"}""",
            """<null""",
            """>{"query": "6", "frames": [{"type": "BookTrain", "slots": [{"value" : "\"_context\"", "attribute" : "departure"}, {"value" : "\"_context\"", "attribute" : "arrival"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby departure_alias, which do you mean: (Beijing_norm), (Shenzhen_norm), (Chengdu_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.test.City","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.BookTrain, slot : departure"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby arrival_alias, which do you mean: (Beijing_norm), (Shenzhen_norm), (Chengdu_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.test.City","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"3\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.BookTrain, slot : arrival"}""",
            """<{"type":"SlotAskAction","payload":"placeHolder?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BookTrain","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "9", "frames": [{"type": "BookTrain", "slots": [{"value" : "\"placeHolder\"", "attribute" : "placeHolder"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"BookTrain_0","payload":"Hi, departure = Beijing arrival = Chengdu"}""",
            """<null""",
        )
        process(multiValueValueClarificationTest)
    }

    @Test
    fun testBoolGate() {
         val BoolGateTest = listOf(
            """>{"query": "1", "frames": [{"type": "BoolGateTestIntent", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"do you need to specify a city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.BoolGate","slot":"status"},{"frame":"io.opencui.test.BoolGateTestIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.booleanGate"}]}""",
            """<{"type":"SlotAskAction","payload":"city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BoolGateTestIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "BoolGateTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "city"}]}]}""",
            """<{"type":"SlotAskAction","payload":"placeHolder?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BoolGateTestIntent","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "BoolGateTestIntent", "slots": [{"value" : "\"Shenzhen\"", "attribute" : "city"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"placeHolder?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BoolGateTestIntent","slot":"placeHolder"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "BoolGateTestIntent", "slots": [{"value" : "\"placeHolder\"", "attribute" : "placeHolder"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"BoolGateTestIntent_0","payload":"Hi, city = Shenzhen placeHolder = placeHolder"}""",
            """<null""",
            """>{"query": "6", "frames": [{"type": "BoolGateTestIntent", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"do you need to specify a city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.BoolGate","slot":"status"},{"frame":"io.opencui.test.BoolGateTestIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "7", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.booleanGate"}]}""",
            """<{"type":"SlotAskAction","payload":"city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BoolGateTestIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "BoolGateTestIntent", "slots": [{"value" : "\"placeHolder\"", "attribute" : "placeHolder"}]}]}""",
            """<{"type":"SlotAskAction","payload":"city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.BoolGateTestIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "9", "frames": [{"type": "BoolGateTestIntent", "slots": [{"value" : "\"Chengdu\"", "attribute" : "city"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"BoolGateTestIntent_0","payload":"Hi, city = Chengdu placeHolder = placeHolder"}""",
            """<null""",
        )
        process(BoolGateTest)
    }

    @Test
    fun testNeverAsk() {
        val NeverAskTest = listOf(
            """>{"query": "1", "frames": [{"type": "NeverAskIntent", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"city?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.NeverAskIntent","slot":"city"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "NeverAskIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "city"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.NeverAskIntent, slot : placeHolder"}""",
            """<{"type":"NeverAskIntent_0","payload":"Hi, city = Beijing placeHolder = associated place holder"}""",
            """<null""",
        )
        process(NeverAskTest)
    }

    @Test
    fun testZep() {
        val ZepTest = listOf(
            """>{"query": "1", "frames": [{"type": "ZepTestIntent", "slots": []}]}""",
            """<{"type":"SlotAskAction","payload":"citySoft?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ZepTestIntent","slot":"citySoft"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "ZepTestIntent", "slots": [{"value" : "\"Shanghai\"", "attribute" : "citySoft"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"zero entry for cityHard"},{"type":"AbortIntentAction","payload":"io.opencui.test.ZepTestIntent is Aborted successfully!"}]}""",
            """<null""",
            """>{"query": "3", "frames": [{"type": "ZepTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "citySoft"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"cityHard?\nWe have following 2 choices: (Shanghai), (Shenzhen)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ZepTestIntent","slot":"cityHard"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "5", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ZepTestIntent, slot : cityHard"}]}""",
            """<{"type":"SlotAskAction","payload":"citiesSoft?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ZepTestIntent","slot":"citiesSoft"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "ZepTestIntent", "slots": [{"value" : "\"Wuhan\"", "attribute" : "citiesSoft"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any citiesSoft else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ZepTestIntent","slot":"citiesSoft"}],"status":"OPEN"}""",
            """>{"query": "8", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"zero entry for citiesHard"},{"type":"AbortIntentAction","payload":"io.opencui.test.ZepTestIntent is Aborted successfully!"}]}""",
            """<null""",
            """>{"query": "9", "frames": [{"type": "ZepTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "citySoft"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"cityHard?\nWe have following 2 choices: (Shanghai), (Shenzhen)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ZepTestIntent","slot":"cityHard"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "11", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ZepTestIntent, slot : cityHard"}]}""",
            """<{"type":"SlotAskAction","payload":"citiesSoft?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ZepTestIntent","slot":"citiesSoft"}],"status":"OPEN"}""",
            """>{"query": "12", "frames": [{"type": "ZepTestIntent", "slots": [{"value" : "\"Wuhan\"", "attribute" : "citiesSoft"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any citiesSoft else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ZepTestIntent","slot":"citiesSoft"}],"status":"OPEN"}""",
            """>{"query": "14", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"citiesHard?\nWe have following 2 choices: (Shenzhen), (Chengdu)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.ZepTestIntent","slot":"citiesHard"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "15", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ZepTestIntent, slot : citiesHard"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"zero entry for citiesHard"},{"type":"MarkFillerDone","payload":"end filler for: citiesHard"}]}""",
            """<{"type":"ZepTestIntent_0","payload":"Hi, \ncitySoft = Beijing \ncityHard = Shenzhen \ncitiesSoft = Wuhan \ncitiesHard = Chengdu"}""",
            """<null""",
        )

        process(ZepTest)
    }

    @Test
    fun testSlotUpdate() {
        val SlotUpdateTest = listOf(
            """>{"query": "1", "frames": [{"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "cityFrom"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Beijing, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Beijing; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "2", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Beijing\"", "attribute" : "oldValue"}, {"value" : "\"Shanghai\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated departure form Beijing_norm to Shanghai_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Shanghai, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "3", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Chengdu\"", "attribute" : "oldValue"}, {"value" : "\"Shenzhen\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SlotAskAction","payload":"You just said Chengdu_norm, do you mean you want to change departure from Shanghai_norm?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SlotUpdate","slot":"confirm"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "No", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Shanghai, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "5", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Chengdu\"", "attribute" : "oldValue"}, {"value" : "\"Shenzhen\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SlotAskAction","payload":"You just said Chengdu_norm, do you mean you want to change departure from Shanghai_norm?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SlotUpdate","slot":"confirm"}],"status":"OPEN"}""",
            """>{"query": "6", "frames": [{"type": "Yes", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated departure form Shanghai_norm to Shenzhen_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Shenzhen, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Shenzhen; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "7", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Shenzhen\"", "attribute" : "oldValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"UpdatePromptAction","payload":"UPDATED PROMPTS for filler cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What do you want for departure?\nWe have following 2 choices: (Beijing_norm), (Shanghai_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityFrom"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            // cityFrom = null; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "8", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"}]}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Beijing, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Beijing; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "9", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Shanghai\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated departure form Beijing_norm to Shanghai_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Shanghai, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "10", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"io.opencui.test.SlotUpdateTestIntent.cityFrom\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"UpdatePromptAction","payload":"UPDATED PROMPTS for filler cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What do you want for departure?\nWe have following 2 choices: (Beijing_norm), (Shanghai_norm)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityFrom"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            // cityFrom = null; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "11", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"}]}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Beijing, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Beijing; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "12", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Shanghai\"", "attribute" : "newValue", "type": "io.opencui.test.City"}, {"value" : "\"Beijing\"", "attribute" : "oldValue", "type": "io.opencui.test.City"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityFrom"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityFrom"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated departure form Beijing_norm to Shanghai_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"cityFrom is Shanghai, cityTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"cityTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = null; citiesFrom = []; citiesTo = []
            """>{"query": "13", "frames": [{"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "cityTo"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"citiesFrom?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesFrom"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Beijing; citiesFrom = []; citiesTo = []
            """>{"query": "14", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Chengdu\"", "attribute" : "newValue", "type": "io.opencui.test.City"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby , which do you mean: (departure), (arrival)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.core.SlotType","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "15", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalSlot"}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : cityTo"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=cityTo"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated arrival form Beijing_norm to Chengdu_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"citiesFrom?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesFrom"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = []; citiesTo = []
            """>{"query": "16", "frames": [{"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "citiesFrom"}], "packageName": "io.opencui.test"}, {"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Beijing\"", "attribute" : "citiesFrom"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"citiesTo?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Beijing, Beijing]; citiesTo = []
            """>{"query": "17", "frames": [{"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Chengdu\"", "attribute" : "citiesTo"}], "packageName": "io.opencui.test"}, {"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Shenzhen\"", "attribute" : "citiesTo"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Beijing, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "18", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Beijing\"", "attribute" : "oldValue"}, {"value" : "\"Shanghai\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.citiesFrom\"", "attribute" : "originalSlot"}, {"value" : "\"1\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : citiesFrom._item"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesFrom._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated the 1st origins form Beijing_norm to Shanghai_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Shanghai, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "19", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Beijing\"", "attribute" : "oldValue"}, {"value" : "\"Beijing\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.citiesFrom\"", "attribute" : "originalSlot"}, {"value" : "\"1\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SlotAskAction","payload":"You just said Beijing_norm, do you mean you want to change 1st origins from Shanghai_norm?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SlotUpdate","slot":"confirm"}],"status":"OPEN"}""",
            """>{"query": "20", "frames": [{"type": "Yes", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : citiesFrom._item"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesFrom._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated the 1st origins form Shanghai_norm to Beijing_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Beijing, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "21", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Beijing\"", "attribute" : "oldValue"}, {"value" : "\"Shanghai\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.citiesFrom\"", "attribute" : "originalSlot"}, {"value" : "\"3\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"There's no 3rd value in origins"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdate&slot=index"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"There are multiple values in origins. Which one do you want to change?\n1. 1st value: Beijing_norm\n2. 2nd value: Beijing_norm"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdate","slot":"index"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "22", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"1\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : index"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : citiesFrom._item"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesFrom._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated the 1st origins form Beijing_norm to Shanghai_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Shanghai, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "23", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Chengdu\"", "attribute" : "oldValue"}, {"value" : "\"Beijing\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.citiesFrom\"", "attribute" : "originalSlot"}, {"value" : "\"3\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"There's no 3rd value in origins"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdate&slot=index"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SeqAction","payload":[{"type":"MarkFillerDone","payload":"end filler for: index"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT value is null for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"TextOutputAction","payload":"We have no clue what you are talking about."}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Shanghai, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "24", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Shanghai\"", "attribute" : "oldValue"}, {"value" : "\"io.opencui.test.SlotUpdateTestIntent.citiesFrom\"", "attribute" : "originalSlot"}, {"value" : "\"1\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesFrom._item"},{"type":"UpdatePromptAction","payload":"UPDATED PROMPTS for filler citiesFrom._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"What do you want for 1st origins?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesFrom"}],"status":"OPEN"}""",
            """>{"query": "25", "frames": [{"type": "SlotUpdateTestIntent", "slots": [{"value" : "\"Zhengzhou\"", "attribute" : "citiesFrom"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Zhengzhou, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "26", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Zhengzhou\"", "attribute" : "oldValue", "type": "io.opencui.test.City"}, {"value" : "\"Shenzhen\"", "attribute" : "newValue", "type": "io.opencui.test.City"}, {"value" : "\"1\"", "attribute" : "index"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : citiesFrom._item"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesFrom._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated the 1st origins form Zhengzhou_norm to Shenzhen_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Shenzhen, Beijing]; citiesTo = [Chengdu, Shenzhen]
            """>{"query": "27", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"Chengdu\"", "attribute" : "oldValue", "type": "io.opencui.test.City"}, {"value" : "\"Shenzhen\"", "attribute" : "newValue", "type": "io.opencui.test.City"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"target?\nby , which do you mean: (arrival), (destinations)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.ValueClarification${'$'}io.opencui.core.SlotType","slot":"target"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "28", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.ValueClarification, slot : target"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalSlot"}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : index"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdateTestIntent, slot : citiesTo._item"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.SlotUpdateTestIntent&slot=citiesTo._item"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated the 1st destinations form Chengdu_norm to Shenzhen_norm for you"}""",
            """<{"type":"SlotAskAction","payload":"any citiesTo else?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.SlotUpdateTestIntent","slot":"citiesTo"}],"status":"OPEN"}""",
            // cityFrom = Shanghai; cityTo = Chengdu; citiesFrom = [Shenzhen, Beijing]; citiesTo = [Shenzhen, Shenzhen]
            """>{"query": "29", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
            """<{"type":"SlotUpdateTestIntent_0","payload":"Hi, \ncityFrom = Shanghai \ncityTo = Chengdu \ncitiesFrom = Shenzhen, Beijing \ncitiesTo = Shenzhen, Shenzhen"}""",
            """<null""",
        )
        process(SlotUpdateTest)
    }

    @Test
    fun testEarlyTermination() {
        val EarlyTerminationTest = listOf(
            """>{"query": "1", "frames": [{"type": "EarlyTerminationIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.EarlyTerminationFrame","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "EarlyTerminationFrame", "slots": [{"value" : "\"aaa\"", "attribute" : "a"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"MarkFillerDone","payload":"end filler for: EarlyTerminationIntent"},{"type":"TextOutputAction","payload":"we don't have choices that meet your requirements, intent terminated"}]}""",
            """<null""",
        )
        process(EarlyTerminationTest)
    }

    @Test
    fun testSoftEarlyTermination() {
        val SoftEarlyTerminationTest = listOf(
            """>{"query": "1", "frames": [{"type": "SoftEarlyTerminationIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"a?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.EarlyTerminationFrame","slot":"a"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "EarlyTerminationFrame", "slots": [{"value" : "\"aaa\"", "attribute" : "a"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"MarkFillerFilled","payload":"end filler for: SoftEarlyTerminationIntent"},{"type":"TextOutputAction","payload":"we don't have choices that meet your requirements, intent terminated"}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of this intent and f.a value aaa"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SoftEarlyTerminationIntent"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.confirmation"}]}""",
            """<{"type":"SoftEarlyTerminationIntent_1","payload":"soft early terminated response, should appear"}""",
            """<null""",
        )
        process(SoftEarlyTerminationTest)
    }


    @Test
    fun testValueRecommendation() {
        val ValueRecommendationTest = listOf(
            """>{"query": "1", "frames": [{"type": "ValueRecommendationTest", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"s?\nWe have following 2 choices: (1), (2)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ReturnValueTestIntent","slot":"b"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": []}""",
            """<{"type":"TextOutputAction","payload":"I did not get that."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ReturnValueTestIntent","slot":"b"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "ReturnValueTestIntent", "slots": [{"value" : "2", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ReturnValueTestIntent, slot : b"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.ReturnValueTestIntent, slot : result"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"s?\nWe have following 2 choices: (ccc), (ddd)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecommendationTest","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "ValueRecommendationTest", "slots": [{"value" : "\"ccc\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecommendationTest, slot : s"}]}""",
            """<{"type":"ValueRecommendationTest_0","payload":"Hi, value recommendation test response s = ccc"}""",
            """<null""",
        )
        process(ValueRecommendationTest)
    }

    @Test
    fun testDirectlyFillMultiValueSlot() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "DirectlyFillMultiValueSlotTest", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"payMethod?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.DirectlyFillMultiValueSlotTest","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "DirectlyFillMultiValueSlotTest", "slots": [{"value" : "\"visa\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"anything else?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.DirectlyFillMultiValueSlotTest","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "DirectlyFillMultiValueSlotTest", "slots": [{"value" : "\"mastercard\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"anything else?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.DirectlyFillMultiValueSlotTest","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
                """<{"type":"SlotAskAction","payload":"s?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.DirectlyFillMultiValueSlotTest","slot":"s"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "DirectlyFillMultiValueSlotTest", "slots": [{"value" : "\"aaa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.DirectlyFillMultiValueSlotTest, slot : payMethodListCopy"}]}""",
                """<{"type":"DirectlyFillMultiValueSlotTest_0","payload":"Hi, value recommendation test response s = aaa payMethodListCopy : visa, mastercard"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testMVEntryConfirmation() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"s?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"s"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [{"value" : "\"aaa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"payMethod?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [{"value" : "\"visa\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"you chose visa"}""",
                """<{"type":"SlotAskAction","payload":"anything else?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [{"value" : "\"mastercard\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"you chose mastercard"}""",
                """<{"type":"SlotAskAction","payload":"anything else?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
                """<{"type":"MVEntryConfirmationTestIntent_0","payload":"Hi, value recommendation test response s = aaa payMethodList = visa, mastercard"}""",
                """<null""",
                """>{"query": "6", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"s?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"s"}],"status":"OPEN"}""",
                """>{"query": "7", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [{"value" : "\"aa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"payMethod?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MVEntryConfirmationTestIntent","slot":"payMethodList"}],"status":"OPEN"}""",
                """>{"query": "8", "frames": [{"type": "MVEntryConfirmationTestIntent", "slots": [{"value" : "\"visa\"", "attribute" : "payMethodList"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"anything else?"}""",
                """>{"query": "9", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.hasMore"}]}""",
                """<{"type":"MVEntryConfirmationTestIntent_0","payload":"Hi, value recommendation test response s = aa payMethodList = visa"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testValueCheck() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "VCTestIntent", "slots": [{"value" : "1", "attribute" : "a"}, {"value" : "\"c\"", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"b fails"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.VCTestIntent&slot=b"},{"type":"RefocusAction","payload":""}]}""",
                """<{"type":"SlotAskAction","payload":"b?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.VCTestIntent","slot":"b"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "VCTestIntent", "slots": [{"value" : "\"b\"", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"c?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.VCTestIntent","slot":"c"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "VCTestIntent", "slots": [{"value" : "true", "attribute" : "c"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"d?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.VCTestIntent","slot":"d"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "VCTestIntent", "slots": [{"value" : "\"a\"", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"a, b and c fail"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.VCTestIntent&slot=a, target=io.opencui.test.VCTestIntent&slot=b, target=io.opencui.test.VCTestIntent&slot=c"},{"type":"RefocusAction","payload":""}]}""",
                """<{"type":"SlotAskAction","payload":"a?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.VCTestIntent","slot":"a"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "VCTestIntent", "slots": [{"value" : "1", "attribute" : "a"}, {"value" : "\"b\"", "attribute" : "b"}, {"value" : "true", "attribute" : "c"}, {"value" : "\"d\"", "attribute" : "d"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"VCTestIntent_0","payload":"Hi, value check test response a = 1 b = b c = true"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testValueReCheck() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "1", "attribute" : "a"}, {"value" : "\"b\"", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"c?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecheckTestIntent","slot":"c"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "true", "attribute" : "c"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"a and c fails"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueRecheckTestIntent&slot=a"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueRecheckTestIntent&slot=c"},{"type":"RecheckAction","payload":"RECHECK SLOT : target=io.opencui.test.ValueRecheckTestIntent&slot=b"},{"type":"RefocusAction","payload":""}]}""",
                """<{"type":"SlotAskAction","payload":"a?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecheckTestIntent","slot":"a"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "2", "attribute" : "a"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"b fails"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.ValueRecheckTestIntent&slot=b"}]}""",
                """<{"type":"SlotAskAction","payload":"b?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecheckTestIntent","slot":"b"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "\"a\"", "attribute" : "b"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"c?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecheckTestIntent","slot":"c"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "true", "attribute" : "c"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"d?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecheckTestIntent","slot":"d"}],"status":"OPEN"}""",
                """>{"query": "6", "frames": [{"type": "ValueRecheckTestIntent", "slots": [{"value" : "\"d\"", "attribute" : "d"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"ValueRecheckTestIntent_0","payload":"Hi, value check test response a = 2 b = a c = true"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testKernelIntent() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "MainWithKernelIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"Good day!"}""",
                """<{"type":"SlotAskAction","payload":"What is your cell number?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.UserInit","slot":"cellPhone"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "UserInit", "slots": [{"value" : "\"12345678910\"", "attribute" : "cellPhone"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"What is your name?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.UserInit","slot":"userName"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "Greeting", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"What is your name?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.UserInit","slot":"userName"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "UserInit", "slots": [{"value" : "\"init_user\"", "attribute" : "userName"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"What can I do for you? (MainWithKernelIntent)"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MainWithKernelIntent","slot":"skills"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "Greeting", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"Good day!"}""",
                """<{"type":"SlotAskAction","payload":"What else can I do for you? (MainWithKernelIntent)"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.test.MainWithKernelIntent","slot":"skills"}],"status":"OPEN"}""",
        )
        process(test)
    }

    @Test
    fun testOutlierValueForValueRec() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "ValueRecOutlierValueIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"s?\nWe have following 3 choices: (a), (b), (c)."}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecOutlierValueIntent","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"6\"", "attribute" : "index"}]}]}""",
                """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"outlier index : 6"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"s?\nWe have following 3 choices: (a), (b), (c)."}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecOutlierValueIntent","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "3", "frames": [{"type": "ValueRecOutlierValueIntent", "slots": [{"value" : "\"d\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"TextOutputAction","payload":"outlier value: d"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=page, target=io.opencui.core.PagedSelectable&slot=conditionMap, target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"s?\nWe have following 3 choices: (a), (b), (c)."}""",
                """<{"activeFrames":[{"frame":"io.opencui.test.ValueRecOutlierValueIntent","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "ValueRecOutlierValueIntent", "slots": [{"value" : "\"c\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"SeqAction","payload":[{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : conditionMap"},{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : page"},{"type":"ReinitAction","payload":"REINIT SLOT : target=io.opencui.core.PagedSelectable&slot=index"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.core.PagedSelectable&slot=index"}]}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ValueRecOutlierValueIntent, slot : s"}]}""",
                """<{"type":"TextOutputAction","payload":"s=c"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testSepNo() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "TestSepNoIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"s?\nonly a left for s, would u like it?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.TestSepNoIntent","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "2", "frames": [{"type": "No", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"AbortIntentAction","payload":"io.opencui.test.TestSepNoIntent is Aborted successfully!"}]}""",
                """<null""",
                """>{"query": "3", "frames": [{"type": "TestSepNoIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"s?\nonly a left for s, would u like it?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.TestSepNoIntent","slot":"s"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "4", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.confirmation"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.TestSepNoIntent, slot : s"}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"ss?\nonly a left for ss, would u like it?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.TestSepNoIntent","slot":"ss"},{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "5", "frames": [{"type": "Yes", "slots": [], "packageName": "io.opencui.core.confirmation"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.TestSepNoIntent, slot : ss"}]}""",
                """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
                """<{"type":"SlotAskAction","payload":"else ss?\nonly a left for ss, would u like it?"}""",
                """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.TestSepNoIntent","slot":"ss"},{"frame":"io.opencui.core.HasMore","slot":"status"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
                """>{"query": "6", "frames": [{"type": "No", "slots": [], "packageName": "io.opencui.core.confirmation"}]}""",
                """<{"type":"SeqAction","payload":[{"type":"MarkFillerDone","payload":"end filler for: ss"}]}""",
                """<{"type":"TextOutputAction","payload":"s=a; ss=a"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testFreeActionConfirmation() {
        val test = listOf(
            """>{"query": "1", "frames": [{"type": "FreeActionConfirmationTestIntent", "slots": [{"value" : "\"aaa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of string value aaa"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.core.FreeActionConfirmation","slot":"status"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "No", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
            """<{"type":"SlotAskAction","payload":"What do you want to do next? You can change your choice before, leave the task and more."}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.FreeActionConfirmation","slot":"action"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "SlotUpdate", "slots": [{"value" : "\"aaa\"", "attribute" : "oldValue"}, {"value" : "\"bbb\"", "attribute" : "newValue"}, {"value" : "\"io.opencui.test.FreeActionConfirmationTestIntent.s\"", "attribute" : "originalSlot"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.SlotUpdate, slot : originalValue"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.FreeActionConfirmationTestIntent, slot : s"},{"type":"CleanupAction","payload":"CLEANUP SLOT : target=io.opencui.test.FreeActionConfirmationTestIntent&slot=s"},{"type":"RefocusAction","payload":""}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of string value bbb"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.core.FreeActionConfirmation","slot":"status"}],"status":"OPEN"}""",
            """>{"query": "4", "frames": [{"type": "Yes", "slots": [], "packagename": "io.opencui.core.confirmation"}]}""",
            """<{"type":"TextOutputAction","payload":"we have updated io.opencui.test.FreeActionConfirmationTestIntent.s form aaa to bbb for you"}""",
            """<{"type":"TextOutputAction","payload":"s=bbb"}""",
            """<null""",
        )
        process(test)
    }

    @Test
    fun testResumeIntent() {
        val test = listOf(
            """>{"query": "1", "frames": [{"type": "SimpleIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"SlotAskAction","payload":"s?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SimpleIntent","slot":"s"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "SimpleIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"TextOutputAction","payload":"We are in the middle of io.opencui.test.SimpleIntent already, let's continue with the current process."}""",
            """<{"type":"SlotAskAction","payload":"s?"}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.SimpleIntent","slot":"s"}],"status":"OPEN"}""",
            """>{"query": "3", "frames": [{"type": "SimpleIntent", "slots": [{"value" : "\"aaa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"TextOutputAction","payload":"s=aaa"}""",
            """<null""",
        )
        process(test)
    }

    @Test
    fun testExternalEventStrategy() {
        val test = listOf(
                """>{"query": "1", "frames": [{"type": "ExternalEventContainerIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
                """<{"type":"SlotAskAction","payload":"we are waiting for callback..."}""",
                """<null""",
                """>{"query": "2", "frames": [{"type": "ExternalEventIntent", "slots": [{"value" : "\"aaa\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"s=aaa"}""",
                """<{"type":"TextOutputAction","payload":"implicitly confirm this intent.s=aaa"}""",
                """<{"type":"SlotAskAction","payload":"we are waiting for callback for async result..."}""",
                """<null""",
                """>{"query": "3", "frames": [{"type": "ExternalEventIntent", "slots": [{"value" : "\"bbb\"", "attribute" : "s"}], "packageName": "io.opencui.test"}]}""",
                """<{"type":"TextOutputAction","payload":"s=bbb"}""",
                """<{"type":"TextOutputAction","payload":"intent=aaa; result=bbb"}""",
                """<null""",
        )
        process(test)
    }

    @Test
    fun testContextBasedRecommendation() {
        val test = listOf(
            """>{"query": "1", "frames": [{"type": "ContextBasedRecIntent", "slots": [], "packageName": "io.opencui.test"}, {"type": "ContextBasedRecFrame", "attribute": "f", "slots": [{"value" : "\"a\"", "attribute" : "a"}], "packageName": "io.opencui.test"}]}""",
            """<{"type":"DirectlyFillAction","payload":"FILL SLOT for target : io.opencui.test.RecommendationIntentForContextBasedRec, slot : result"}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"We have following 2 choices: (c;c), (d;d)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.ContextBasedRecFrame"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.ContextBasedRecFrame, slot : "}]}""",
            """<{"type":"TextOutputAction","payload":"f=d"}""",
            """<null""",
        )
        process(test)
    }

    @Test
    fun testSlotSepInformConfirmComposite() {
        val test = listOf(
            """>{"query": "1", "frames": [{"type": "SlotDoubleConfirmTestIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"TextOutputAction","payload":"we only have a; we chose it for u"}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.SlotDoubleConfirmTestIntent, slot : slot"}]}""",
            """<{"type":"SlotAskAction","payload":"r u sure of slot value a"}""",
            """<{"activeFrames":[{"frame":"io.opencui.core.Confirmation","slot":"status"},{"frame":"io.opencui.test.SlotDoubleConfirmTestIntent","slot":"slot"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "Yes", "slots": []}]}""",
            """<{"type":"TextOutputAction","payload":"f=a"}""",
            """<null""",
        )
        process(test)
    }

    @Test
    fun testAbstractEntity() {
        val test = listOf(
            """>{"query": "1", "frames": [{"type": "AbstractEntityIntent", "slots": [], "packageName": "io.opencui.test"}]}""",
            """<{"type":"FillAction","payload":"FILL SLOT value is null for target : io.opencui.core.PagedSelectable, slot : index"}""",
            """<{"type":"SlotAskAction","payload":"What would u like?\nWe have following 3 choices: (virtual), (noodle), (salad)."}""",
            """<{"activeFrames":[{"frame":"io.opencui.test.AbstractEntityIntent","slot":"dish"},{"frame":"io.opencui.core.PagedSelectable","slot":"index"}],"status":"OPEN"}""",
            """>{"query": "2", "frames": [{"type": "PagedSelectable", "slots": [{"value" : "\"2\"", "attribute" : "index"}]}]}""",
            """<{"type":"SeqAction","payload":[{"type":"FillAction","payload":"FILL SLOT for target : io.opencui.test.AbstractEntityIntent, slot : dish"}]}""",
            """<{"type":"TextOutputAction","payload":"abstract entity type is io.opencui.test.MainDish; value is noodle"}""",
            """<null""",
        )
        process(test)
    }

    fun serializeSession(session: UserSession) : String {
        val byteArrayOut = ByteArrayOutputStream()
        val objectOut = ObjectOutputStream(byteArrayOut)
        objectOut.writeObject(session)
        return String(Base64.getEncoder().encode(byteArrayOut.toByteArray()))
    }

    fun process(lines: List<String>) {
        // the first line is agent name.
        // the second line is the rules file.
        // val lines = File(expected).bufferedReader().readLines()
        assertTrue(lines.size >= 2)
        val userInfo = UserInfo("test_channel", "test_user", null)
        sessionManager.deleteUserSession(userInfo, botInfo)
        val chatbot = Dispatcher.getChatbot(botInfo)

        var index = 0

        while (index < lines.size) {
            var qsession: UserSession? = sessionManager.getUserSession(userInfo, botInfo)
            qsession = if (qsession != null) {
                // serialize this out and back if we need to test.
                val encodedSession = serializeSession(qsession)
                deserialize(encodedSession, chatbot.getLoader())
            } else {
                sessionManager.createUserSession(userInfo, botInfo)
            }

            val session = qsession!!

            val line = lines[index]
            // line is not blank now.
            assertTrue(line.startsWith(">"), line)

            val input = lines[index].substring(1)
            println(input)
            val responses = try {
                val rawQuery = parseExplanation(input)
                val convertedFrameEvents = dm.convertSpecialFrameEvent(session, rawQuery.frames)
                for (event in convertedFrameEvents) {
                    event.source = EventSource.USER
                }
                val convertedQuery = ParsedQuery(rawQuery.query, convertedFrameEvents)
                timing("Executing response ") {
                    dm.response(convertedQuery, session)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                listOf(ActionResult(ActionLog("Exception", Json.makePrimitive(""), true)))
            }

            val replies : List<ComponentDialogAct> = responses.filter { it.botUtterance != null && it.botOwn }.map { it.botUtterance!!}.flatten()
            val rewrittenReplies = session.rewriteDialogAct(replies)
            for (reply in rewrittenReplies) {
                println("reply = ${reply.templates.pick().invoke()}")
            }

            val replyTextList = responses.filter { it.actionLog != null && it.actionLog!!.isTestable }.map { Json.encodeToString(it.actionLog!!) }

            val expected = replyTextList + (Json.encodeToString(dm.findDialogExpectation(session) ?: NullNode.instance))

            expected.forEach { println(it) }

            val response = getResponseIndex(index + 1, lines)
            val eindex = response.first

            checkEquals(index + 1, eindex - 1, lines, expected)
            index = eindex

            sessionManager.updateUserSession(session.userIdentifier, session.botInfo, session)
        }
    }

    fun parseExplanation(command: String): ParsedQuery {
        return Json.decodeFromString(command)
    }

    fun getResponseIndex(index: Int, lines: List<String>): Pair<Int, List<String>> {
        assertTrue(lines[index - 1].startsWith(">"))
        assertTrue(lines[index].startsWith("<"))
        var lindex = index
        val replies = mutableListOf<String>()
        while (lindex < lines.size && lines[lindex].isNotBlank() && lines[lindex].startsWith("<")) {
            replies.add(lines[lindex].substring(1))
            lindex += 1
        }
        return Pair(lindex, replies.toList())
    }

    fun checkEquals(start: Int, end: Int, lines: List<String>, logs: List<String>) {
        for (i in start..end) {
            val got = if (i - start < logs.size) logs[i - start] else ""
                assertEquals(lines[i].substring(1), got)
        }
    }
}

