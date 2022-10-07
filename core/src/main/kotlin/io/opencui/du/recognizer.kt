package io.opencui.du

import com.sun.jna.platform.mac.SystemB.Timezone
import io.opencui.core.RuntimeConfig
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import io.opencui.serialization.*
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.util.*

/**
 * The normalizer is used to find the all occurrence of entities, and mark it with annotation.
 * Value should be in json format.
 */
class SpanInfo(
        val type: String,
        val start: Int,
        val end: Int,
        val latent: Boolean,
        val value: Any?=null,
        val recognizer: EntityRecognizer? = null,
        val score: Float=2.0f) {
    override fun toString() : String {
        return "$value @($start, $end)"
    }

    fun norm() : String? {
        return recognizer?.getNormedValue(this)
    }
}


/**
 * The context for extractive understanding, it should potentially include things like bot timezone.
 * Later, we might need to introduce user based context.
 */
data class BotExtractionContext(
    val botTimezone: Timezone
)

/**
 * We will have different type of entity recognizer, ducking, list, and maybe others.
 * These will be used by both span detection, global rescoring.
 *
 * Also, each EntityRecognizer will responsible for a set of entities.
 * All EntityRecognizer are assumed to have an companion object which can
 * be used to build the recognizer for the language.
 *
 * We will assume for each agent, we have a separate recognizer, for the ones that need
 * to be shared, we will add a wrapper.
 *
 * We do not assume the same tokenizer will be used across, so we operate on the char level.
 *
 * To support any entity, we need to wire up the EntityType with corresponding serializer
 * so that the compiler know what do.
 *
 * TODO: need to figure out multi valued cases here.
 */
interface EntityRecognizer {
    // val botContext: BotExtractionContext

    /**
     * Takes a user utterance, and current expected types, it emits the recognized entity input
     * entity map, where key is the type of entity, and value is list of spans for that entity type.
     */
    fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<SpanInfo>>)

    /**
     *  Return null if there is no normalization info, or string that can be decoded back to the object
     *  via Json deserialization.
     */
    fun getNormedValue(value: SpanInfo): String?
}


// For now assume we have all the information needed
class DucklingRecognizer(val agent: DUMeta):  EntityRecognizer {
    val client: HttpClient = HttpClient.newHttpClient()
    val url: String = RuntimeConfig.get(DucklingRecognizer::class)
    private val timeOut = 5000L

    // For now just add bot timezone, if we want to handle user based timezone,
    // checkout: https://github.com/RasaHQ/rasa/pull/1280/commits/1d140d2d5b6479cfc9778f69a20b74f3fc49ce31
    val timezone = agent.getTimezone()

    val lang: String = when(val s = agent.getLang()){
        "en" -> "en_GB"
        "zh" -> "zh_XX"
        else -> s
    }

    val supported = mutableSetOf<String>()

    init{
        val entities = agent.getEntities()
        for( key in entities) {
            val entity = agent.getEntityMeta(key)
            if (entity?.recognizer?.find { it == "DucklingRecognizer" } != null) {
                logger.info("$key does need DucklingRecognizer")
                supported.add(key)
            } else {
                logger.info("$key does not need DucklingRecognizer")
            }
        }
    }

    fun getType(items:JsonObject): List<String> {
        return when(val s = (items.get("dim") as JsonPrimitive).content()) {
            "time" -> listOf("java.time.LocalDateTime", "java.time.LocalTime", "java.time.LocalDate", "java.time.YearMonth", "java.time.Year")
            "email" -> listOf("framely.core.Email", "io.opencui.core.Email")
            "phone-number" -> listOf("framely.core.PhoneNumber", "io.opencui.core.PhoneNumber")
            "number" -> listOf("kotlin.Int", "kotlin.Float")
            "ordinal" -> listOf("framely.core.Ordinal", "io.opencui.core.Ordinal")
            else -> listOf("io.opencui.core.${s}")
        }
    }

    fun convert(type: String, items: JsonObject): SpanInfo {
        val start = items.getPrimitive("start").content().toInt()
        val end = items.getPrimitive("end").content().toInt()
        val latent = items.getPrimitive("latent").content().toBoolean()
        val value = items.get("value") as JsonElement
        return SpanInfo(type, start, end, latent, value, this)
    }

    fun fill(input: String, sres: JsonArray, emap: MutableMap<String, MutableList<SpanInfo>>) {
        for (items in sres) {
            val types = getType(items as JsonObject)
            logger.info(types.toString())
            for (typeFullName in types) {
                val ea = convert(typeFullName, items)
                if (ea.norm() == null) continue
                if (!supported.contains(typeFullName)) continue
                val converted = postprocess(input, ea)
                if (converted != null) {
                    if (!emap.containsKey(typeFullName)) {
                        emap[typeFullName] = mutableListOf()
                    }
                    emap[typeFullName]!!.add(converted)
                }
            }
        }
    }

    // TODO: need to change this for different types.
    override fun getNormedValue(value: SpanInfo): String? {
        return when(value.type) {
            "java.time.LocalDateTime" -> parseLocalDateTime(value.value)
            "java.time.LocalTime" -> parseLocalTime(value.value)
            "java.time.LocalDate" -> parseLocalDate(value.value)
            "java.time.YearMonth" -> parseYearMonth(value.value)
            "java.time.Year" -> parseYear(value.value)
            "kotlin.Int" -> parseIt(value.value!!)
            "kotlin.Float" -> parseIt(value.value!!)
            else -> "\"${parseIt(value.value!!)}\""
        }
    }

    fun parseLocalDateTime(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 5, 19)
        return if (strValue != null) Json.encodeToString(LocalDateTime.parse(strValue)) else null
    }

    fun parseLocalTime(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 5, 19, 11)
        return if (strValue != null) Json.encodeToString(LocalTime.parse(strValue)) else null
    }

    fun parseLocalDate(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 4, 10)
        return if (strValue != null) Json.encodeToString(LocalDate.parse(strValue)) else null
    }

    fun parseYearMonth(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 2, 7)
        return if (strValue != null) Json.encodeToString(YearMonth.parse(strValue)) else null
    }

    fun parseYear(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 0, 4)
        return if (strValue != null) Json.encodeToString(Year.parse(strValue)) else null
    }

    fun parseTime(value: JsonObject, grainIndexTarget: Int, len: Int, start:Int = 0): String? {
        val primitive = value["value"] as JsonPrimitive? ?: return null
        val grain = (value["grain"] as JsonPrimitive).content()
        val grainIndex = getGrainIndex(grain)
        if (grainIndex < 0) return null
        if (grainIndex < grainIndexTarget) return null
        return primitive.content().substring(start, len)
    }

    fun parseIt(value: Any): String? {
        value as JsonObject
        if (!value.containsKey("value")) return null
        return (value["value"] as JsonPrimitive).content()
    }

    override fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<SpanInfo>>) {
        // Because of a bug in duckling, we need to call duckling more than once.
        if (types.contains("io.opencui.core.Ordinal")) {
            parseImpl(input, listOf("ordinal"), emap)
        }
        parseImpl(input, listOf(), emap)
    }

    /**
     * Duckling try to understanding things without strong support on expectation, so many things they
     * just do it differently. for example, they will mask "on tuesday" as one word, but this makes other
     * part of pipeline does not know what to do. So this post process aim to change it back.
     *
     * The long term solution for this is replaced duckling with expectation driven understanding.
     */
    fun postprocess(utterance: String, v: SpanInfo) : SpanInfo? {
        if (agent.getLang() == "en") {
            val substr = utterance.substring(v.start).lowercase(Locale.getDefault())
            if (v.type == "java.time.LocalDate") {
                if (substr.startsWith("on ")) {
                    return SpanInfo(v.type, v.start + 3, v.end, v.latent, v.value, v.recognizer, v.score)
                }
                if (substr.startsWith("at ")) return null
            }
            if (v.type == "java.time.LocalTime") {
                if (substr.startsWith("at ")) {
                    return SpanInfo(v.type, v.start + 3, v.end, v.latent, v.value, v.recognizer, v.score)
                }
                if (substr.startsWith("on ")) return null
            }
        }
        return v
    }


    fun parseImpl(input: String, types: List<String>, emap: MutableMap<String, MutableList<SpanInfo>>) {
        val data: MutableMap<String, String> = HashMap()
        data["locale"] = lang
        data["text"] = input
        data["tz"] = timezone

        if (types.isNotEmpty()) {
            data["dims"] = Json.encodeToString(types)
        }

        val request: HttpRequest = HttpRequest.newBuilder()
                .POST(ofFormData(data))
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeOut))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            val body = response.body()
            val elements = Json.parseToJsonElement(body) as JsonArray
            logger.info(elements.toString())
            fill(input, elements, emap)
        } else {
            logger.info(response.body())
        }
    }

    fun ofFormData(data: Map<String, String>): BodyPublisher {
        val builder = StringBuilder()
        for ((key, value) in data) {
            if (builder.isNotEmpty()) {
                builder.append("&")
            }
            builder.append("$key=$value")
        }
        return BodyPublishers.ofString(builder.toString())
    }

    companion object {
        val logger = LoggerFactory.getLogger(DucklingRecognizer::class.java)
        val grains : List<String> = listOf("year", "quarter", "month", "week", "day", "hour", "minute", "second")
        fun getGrainIndex(grain: String) : Int { return grains.indexOf(grain)}
    }
}


class ListRecognizer(val agent: DUMeta) : EntityRecognizer {

    data class TypedLabel(val typeId: Int, val labelId: Int)
    data class TypedMention(val typeId: Int, val mentionId: Int)

    val maxNgramSize = 5

    val typeTable = StringIdTable()
    val labelTable = StringIdTable()
    val mentionTable = StringIdTable()
    val mentionIndex = ArrayList<ArrayList<TypedLabel>>()

    val tokenTable = StringIdTable()
    // from token to all the mentions related to it.
    val tokenIndex = ArrayList<ArrayList<TypedMention>>()

    fun updateMentionIndex(mentionId: Int, labelId: Int, typeId: Int) {
        mentionIndex.ensureCapacity(mentionId + 1)
        if (mentionIndex.size <= mentionId) mentionIndex.add(ArrayList())
        mentionIndex[mentionId].add(TypedLabel(typeId, labelId))
    }

    fun updateTokenIndex(tokenId: Int, mentionId: Int, typeId: Int) {
        tokenIndex.ensureCapacity(tokenId + 1)
        if (tokenIndex.size <= tokenId) tokenIndex.add(ArrayList())
        tokenIndex[tokenId].add(TypedMention(typeId, mentionId))
    }

    private val analyzer: Analyzer? = LanguageAnalzyer.getUnstoppedAnalyzer(agent.getLang())
    private val stopwords: CharArraySet? = LanguageAnalzyer.getStopSet(agent.getLang())

    // This method is used to handle the extractive frame like DontCare and That
    fun collectExtractiveFrame(owner: JsonObject, type: String, processed: HashMap<String, ArrayList<String>>) {
        val ownerId = owner.getPrimitive(DUMeta.OWNERID).content()
        if (ownerId != type) return
        val expressions = owner[DUMeta.EXPRESSIONS]!! as JsonArray
        for (expression in expressions) {
            expression as JsonObject
            if (!expression.containsKey(DUMeta.CONTEXT)) continue
            val contextObject = expression[DUMeta.CONTEXT] as JsonObject
            val typeId = contextObject.getPrimitive(DUMeta.TYPEID).content()
            if (!processed.containsKey(typeId)) processed[typeId] = ArrayList()
            val utterance = expression.getPrimitive(DUMeta.UTTERANCE).content()
            processed[typeId]?.add(utterance)
        }
    }

    // After we collect all the phrases related we add this for recognizer.
    fun memorizeExtractiveFrame(label: String, type:String, processed: HashMap<String, ArrayList<String>>) {
        val phrases = processed[type] ?: return
        val typeId = typeTable.put(type)
        if (phrases.size > 0) {
            val labelId = labelTable.put(label)
            for (phrase in phrases) {
                val mentionId = mentionTable.put(phrase)
                updateMentionIndex(mentionId, labelId, typeId)
            }
        }
    }

    init {
        logger.info("Init ListRecognizer...")
        // We can have two kind of DontCare, proactive, and reactive. The DontCare on the entity
        // is also proactive, meaning user can say it directly. instead of just replying prompt.
        val processedDontcare = HashMap<String, ArrayList<String>>()
        val processedThat = HashMap<String, ArrayList<String>>()
        val fullMatches = HashMap<Pair<String, String>, MutableSet<String>>()
        // frame dontcare annotations
        val exprOwners = agent.getFrameExpressions()
        for (owner in exprOwners) {
            owner as JsonObject
            collectExtractiveFrame(owner, StateTracker.FullDontCare, processedDontcare)
            collectExtractiveFrame(owner, StateTracker.FullThat, processedThat)
        }

        // Populate the typeId
        agent.getEntities().map { typeTable.put(it) }

        // Handle every type here.
        for (type in agent.getEntities()) {
            val typeId = typeTable.getId(type)!!

            // TODO(sean): we should get the name instead of using label here.
            // now we assume that we have normed token.
            // TODO(sean): assume normed can not be recognized unless it is also listed in the rest.
            val partialIndex = HashMap<String, MutableList<String>>()

            fun add(entryLabel: String, expressions: List<String>) {
                // TODO(sean): again, the norm need to be language related.
                val labelId = labelTable.put(entryLabel)
                for (mention in expressions) {
                    val key = mention.lowercase().trim{ it.isWhitespace() }
                    val mentionId = mentionTable.put(key)
                    // Handle full match.
                    updateMentionIndex(mentionId, labelId, typeId)

                    // Handle partial match.
                    val tokens = tokenize(key)
                    for (token in tokens) {
                        val tokenId = tokenTable.put(token)
                        updateTokenIndex(tokenId, mentionId, typeId)
                        if (!partialIndex.containsKey(token)) partialIndex[token] = mutableListOf()
                        if (!fullMatches.containsKey(Pair(token, type))) {
                            fullMatches[Pair(token, type)] = mutableSetOf()
                        }
                        fullMatches[Pair(token,type)]!!.add(entryLabel)
                        partialIndex[token]!!.add(key)
                    }
                }
            }

            // for internal node
            val meta = agent.getEntityMeta(type)
            val children = meta?.children ?: emptyList()
            for (child in children) {
                // TODO(sean): Use * to mark the internal node, need to ake sure that is pattern is stable
                val entryLabel = "*$child"
                val expressions = agent.getTriggers(child)
                add(entryLabel, expressions)
            }

            // Actual instances.

            val content = agent.getEntityInstances(type)
            logger.info("process entity type $type with ${content.size} entries.")
            for ((entryLabel, expressions) in content) {
                add(entryLabel,expressions)
            }

            // process entity dontcare annotations
            if (processedDontcare.containsKey(type)) {
                memorizeExtractiveFrame(StateTracker.DontCareLabel, StateTracker.FullDontCare, processedDontcare)
            }

            // Let's handle the pronoun that here. Notice currently that is only via extractive understanding.
            if (processedThat.containsKey(type)) {
                memorizeExtractiveFrame(StateTracker.ThatLabel, StateTracker.FullThat, processedThat)
            }
        }
    }

    fun tokenize(text: String) : List<String> {
        val tokenStream = analyzer!!.tokenStream(QUERY, text)
        val attr = tokenStream.addAttribute(CharTermAttribute::class.java)
        tokenStream.reset()
        val result = ArrayList<String>()
        while(tokenStream.incrementToken()) {
            result.add(attr.toString())
        }
        tokenStream.close()
        return result
    }


    // TODO(sean) we need to make sure input is simple space separated for en.
    override fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<SpanInfo>>) {
        val tokenStream = analyzer!!.tokenStream(QUERY, input)
        val attr = tokenStream.addAttribute(OffsetAttribute::class.java)
        tokenStream.reset()
        val spanlist = ArrayList<Pair<Int, Int>>()
        // Add all the tokens
        while (tokenStream.incrementToken()) {
            val start = attr.startOffset()
            val end = attr.endOffset()
            spanlist.add(Pair(start, end))
        }

        tokenStream.close()

        // We should try the longest match.
        val typedSpans = HashMap<Int, MutableList<Pair<Int, Int>>>()
        val partialMatch = mutableListOf<SpanInfo>()
        for (i in 0..spanlist.size) {
            for (k in 0..maxNgramSize) {
                if ( i + k >= spanlist.size) continue
                val range = IntRange(spanlist[i].first, spanlist[i+k].second-1)
                val key = input.slice(range)

                val mentionId = mentionTable.getId(key)
                println("getting $mentionId for $key")
                if (mentionId != null) {
                    val occurrences = mentionIndex[mentionId]
                    for (occurrence in occurrences) {
                        val typeId = occurrence.typeId
                        val labelId = occurrence.labelId
                        val type = typeTable.getString(typeId)
                        val label = labelTable.getString(labelId)

                        if (!emap.containsKey(type)) {
                            emap[type] = mutableListOf()
                        }

                        if (!typedSpans.containsKey(typeId)) {
                           typedSpans[typeId] = mutableListOf()
                        }

                        typedSpans[typeId]!!.add(Pair(spanlist[i].first, spanlist[i + k].second))
                        emap[type]!!.add(SpanInfo(type, spanlist[i].first, spanlist[i + k].second, false, label, this))
                    }
                }  else {
                    // when this is not mention match
                    val tokenId = tokenTable.getId(key)
                    if (tokenId != null) {
                        val occurrences = tokenIndex[tokenId]
                        for (occurrence in occurrences) {
                            val typeId = occurrence.typeId
                            val type = typeTable.getString(typeId)
                            val label = PARTIALMATCH
                            partialMatch.add(SpanInfo(type, spanlist[i].first, spanlist[i + k].second, false, label, this))
                        }
                    }
                }
            }
        }

        for (span in partialMatch) {
            println(partialMatch)
            val target = Pair(span.start, span.end)
            val typeId = typeTable.getId(span.type)
            println("getting for ${span.type} : $typeId")
            val listOfFullMatchedSpan = typedSpans[typeId]
            if (listOfFullMatchedSpan != null && !covered(target, listOfFullMatchedSpan)) {
                println("getting for ${span.type} : $typeId 2")
                logger.debug("Covered $target is not covered by $listOfFullMatchedSpan with $span" )
                if (!emap.containsKey(span.type)) {
                    emap[span.type] = mutableListOf()
                }
                emap[span.type]!!.add(span)
            }
        }
        println(emap)
    }

    private fun covered(target: Pair<Int, Int>, ranges: List<Pair<Int, Int>>): Boolean {
        for (range in ranges) {
            if ((target.first >= range.first && target.second <= range.second)) {
                return true
            }
        }
        return false
    }

    fun findRelatedEntity(type: String, token: String): List<String>? {
        val typeId = typeTable.getId(type)
        val tokenId = tokenTable.getId(token) ?: return null
        return tokenIndex[tokenId].filter { it.typeId == typeId }.map { mentionTable.getString(it.mentionId) }
    }


    override fun getNormedValue(value: SpanInfo): String? {
        return when (value.type) {
            "kotlin.Boolean" -> value.value as String?
            "java.time.ZoneId" -> Json.encodeToString(ZoneId.of(value.value as String))
            else -> "\"${value.value}\""
        }
    }

    companion object {
        const val PARTIALMATCH = "_partial_match"
        const val QUERY = "QUERY"


        fun isInternal(label: String): Boolean = label.startsWith("*")

        fun isPartialMatch(norm: String?) : Boolean {
            return norm == "\"_partial_match\""
        }

        val logger = LoggerFactory.getLogger(ListRecognizer::class.java)
    }
}

fun defaultRecognizers(agent: DUMeta) : List<EntityRecognizer> {
    return listOf(
        ListRecognizer(agent),
        DucklingRecognizer(agent)
    )
}

fun List<EntityRecognizer>.recognizeAll(utterance:String, types: List<String>, emap: MutableMap<String, MutableList<SpanInfo>>) {
    forEach { it.parse(utterance, types, emap) }
}

// Simply provide a way to convert string to id, or id to string back.
class StringIdTable {
    val stringToId = HashMap<String, Int>()
    val idToString = ArrayList<String>()
    fun put(key: String) : Int {
        if (!stringToId.containsKey(key)) {
            stringToId[key] = idToString.size
            idToString.add(key)
        }
        return stringToId[key]!!
    }

    fun getString(index: Int) : String {
        return idToString[index]
    }

    fun getId(key: String): Int? = stringToId[key]
}
