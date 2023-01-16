package io.framely.provider


import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.*
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AppendValuesResponse
import com.google.api.services.sheets.v4.model.UpdateValuesResponse
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import io.opencui.core.*
import io.opencui.provider.IConnection
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter


/**
 * Google Sheets Provider allows you to implement some function in sql like query language
 * or in native Kotlin.
 */
data class GoogleSheetsConnection(val cfg: Configuration): IConnection {
    // Use Google http client for Google services.
    // This provider is based on Google sheets api v4 using both java sdk
    // and or using restful

    val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
    val spreadsheetId: String = cfg[SPREADSHEETID]!! as String
    val credentialJsonStr: String = cfg[CREDENTIAL]!! as String
    val backoff = ExponentialBackOff.Builder()
        .setInitialIntervalMillis(500)
        .setMaxElapsedTimeMillis(900000)
        .setMaxIntervalMillis(6000)
        .setMultiplier(1.5)
        .setRandomizationFactor(0.5)
        .build()

    // make sure the token is refreshed.
    val credentials: GoogleCredentials = GoogleCredentials
        .fromStream(ByteArrayInputStream(credentialJsonStr.toByteArray(Charsets.UTF_8)))
        .createScoped(SCOPES)

    val requestInitializer: HttpRequestInitializer = HttpCredentialsAdapter(credentials)

    fun invoke(
        functionMeta: Map<String, String>,
        context: Map<String, Any?>,
        body: String): JsonElement {
        // The default function implementation for this provider is based on
        // https://developers.google.com/chart/interactive/docs/querylanguage#setting-the-query-in-the-data-source-url
        // And to make things easier, we use Google java http client.
        // https://www.baeldung.com/google-http-client
        val reqFactory = HTTP_TRANSPORT.createRequestFactory(requestInitializer)


        // this calls out to
        val url = GenericUrl("https://docs.google.com/a/google.com/spreadsheets/d/$spreadsheetId/gviz/tq")
        url.put("tq", body)
        val sheetId = functionMeta[SHHEETID]
        if (sheetId != null) url.put(SHHEETID, sheetId)
        val range = functionMeta[RANGE]
        if (range != null) url.put(RANGE, range)
        val gid = functionMeta[GID]
        if (gid != null) url.put(GID, gid)

        val req: HttpRequest = reqFactory.buildGetRequest(url)

        // Use exponential backoff while we are at it.
        req.unsuccessfulResponseHandler = HttpBackOffUnsuccessfulResponseHandler(backoff)

        val resp: HttpResponse = req.execute()
        val resBody = resp.parseAsString()
        val start = resBody.indexOf('{')
        val end = resBody.lastIndexOf('}') + 1

        val resJson = Json.parseToJsonElement(resBody.substring(start, end)) as JsonObject
        val values = parseQueryResult(resJson)
        return Json.parseToJsonElement(Json.encodeToString(values))
    }
    override fun <T> svInvoke(
        functionMeta: Map<String, String>,
        context: Map<String, Any?>,
        body: String,
        converter: Converter<T>): T {
        return converter(invoke(functionMeta, context, body))
    }

    override fun <T> mvInvoke(
        functionMeta: Map<String, String>,
        context: Map<String, Any?>,
        body: String,
        converter: Converter<T>): List<T> {
        val values = invoke(functionMeta, context, body) as JsonArray
        val results = mutableListOf<T>()
        values.map { results.add(converter(it)) }
        return results
    }


    // We need to provide low level update and append.
    fun update(range: String, values: List<Any>, valueInputOption: String): UpdateValuesResponse? {

        // Create the sheets API client
        val service: Sheets = Sheets.Builder(
            HTTP_TRANSPORT,
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName("Sheets samples")
            .build()
        var result: UpdateValuesResponse? = null
        try {
            val newValues = convert(values)
            // Updates the values in the specified range.
            val body = ValueRange()
                .setValues(newValues)
            result = service.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption(valueInputOption)
                .execute()
            System.out.printf("%d cells updated.", result.getUpdatedCells())
        } catch (e: GoogleJsonResponseException) {
            // TODO(developer) - handle error appropriately
            val error: GoogleJsonError = e.getDetails()
            if (error.getCode() == 404) {
                System.out.printf("Spreadsheet not found with id '%s'.\n", spreadsheetId)
            } else {
                throw e
            }
        }
        return result
    }

    fun append(range: String, values: List<Any>, valueInputOption: String): AppendValuesResponse? {

        // Create the sheets API client
        val service: Sheets = Sheets.Builder(
            HTTP_TRANSPORT,
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName("Sheets samples")
            .build()
        var result: AppendValuesResponse? = null

        try {
            val newValues = convert(values)
            // Append the values
            val body2 = ValueRange()
                .setValues(newValues)
            result = service.spreadsheets().values()
                .append(spreadsheetId, range, body2)
                .setValueInputOption(valueInputOption)
                .execute()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return result
    }

    private fun convert(values: List<Any>): MutableList<MutableList<Any>> {
        val fieldValues = mutableListOf<Any>()
        values.map { fieldValues.add(it) }
        return mutableListOf(fieldValues)
    }

    fun toQueryString(x: Any): String {
        return when(x) {
            is LocalDate -> """date "${x.format(dateFormat)}""""
            is LocalTime -> """timeofday "${x.format(timeFormat)}""""
            is LocalDateTime -> """datetime "${x.format(dateTimeFormat)}""""
            is Number, is Boolean -> x.toString()
            else -> """"$x""""
        }
    }

    override fun close() {}

    companion object {
        private val JSON_FACTORY: com.google.api.client.json.JsonFactory = GsonFactory.getDefaultInstance()
        val logger = LoggerFactory.getLogger(GoogleSheetsConnection::class.java)

        const val SPREADSHEETID = "spreadsheet_id"
        const val CREDENTIAL = "credential"
        const val SHHEETID = "sheet"
        const val GID = "gid"
        const val RANGE = "range"

        fun parseQueryResult(resJson: JsonObject): List<Map<String, Any?>> {
            val tableJson = resJson.getJsonObject("table")
            val labels = getLabels(tableJson.getJsonArray("cols"))
            val valueJson = tableJson.getJsonArray("rows")
            return valueJson.map { getValues(it as JsonObject, labels) }
        }

        private fun getValues(cell: JsonObject, labels: List<Pair<String, String>>): Map<String, Any?> {
            val c = cell.getJsonArray("c")
            return labels.zip(c.toList()).map{Pair(it.first.first, preConvert(it.first, it.second as JsonObject))}.toMap()
        }

        private fun preConvert(key: Pair<String, String>, value: JsonObject) : JsonElement {
            return when(key.second) {
                "date" -> value.getString("v")
                    .substringBefore(")")
                    .substring(5)
                    .split(",")
                    .map{ it.toInt() }
                    .run{ Json.encodeToJsonElement(LocalDate.of(get(0), get(1) + 1 , get(2))) }
                "datetime" -> value.getString("v")
                    .substringBefore(")")
                    .substring(5)
                    .split(",")
                    .map{ it.toInt() }
                    .run{ Json.encodeToJsonElement(LocalDateTime.of(get(0), get(1) + 1, get(2), get(3), get(4), get(5))) }
                else -> value.get("v")
            }
        }

        private fun getLabels(headerJson: JsonArray): List<Pair<String, String>> {
            return headerJson.map { Pair((it as JsonObject).getString("label"), (it as JsonObject).getString("type")) }
        }

        /**
         * Global instance of the scopes required by this quickstart.
         * If modifying these scopes, delete your previously saved tokens/ folder.
         */
        private val SCOPES = listOf(SheetsScopes.SPREADSHEETS)
        private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}