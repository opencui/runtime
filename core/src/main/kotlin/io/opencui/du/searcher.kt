package io.opencui.du

import io.opencui.core.Dispatcher
import io.opencui.serialization.*
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.RAMDirectory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList

/**
 * We assume the training expression will be indexed the code generation phase.
 * And this code is used to do search to reduce the number expression that we need
 * to go through.
 *
 * Expression: "I like to watch a <Moive>"
 * context: Frame that we r in, some expression are weak, and only be triggered where context is right.
 * target: the frame that this expression is attached too, payload
 *
 * if context is default, but target is not null, expression is triggering
 * if context is not null, and target is the same, expression is not trigger, but target is context.
 * if context is not null, and target is not same, we can deal with case for confirmation.
 */

data class ScoredDocument(var score: Float, val document: Document) {
    val probes: String = document.getField(PROBE).stringValue()
    val utterance: String = document.getField(UTTERANCE).stringValue()
    val typedExpression: String = document.getField(EXPRESSION).stringValue()
    val ownerFrame: String = document.getField(OWNER).stringValue()
    val slots: String = document.getField(SLOTS).stringValue()
    val contextFrame: String? = document.getField(CONTEXTFRAME)?.stringValue()
    val contextSlot: String? = document.getField(CONTEXTSLOT)?.stringValue()
    val slotTypes: List<String> = document.getFields(SLOTTYPE).map {it.stringValue()}
    val entailedSlots: List<String> = document.getFields(PARTIALEXPRESSION).map {it.stringValue() }
    val label: String = if (document.get(LABEL) == null) "" else document.get(LABEL)

    // whether it is exact match.
    var exactMatch: Boolean = false
    var hasAllSlots: Boolean = true

    fun getQualifiedSlotNames() : List<String> {
        return slots.split(",")
    }

    companion object {
        const val PROBE = "probe"
        const val UTTERANCE = "utterance"
        const val OWNER = "owner"
        const val OWNERSLOT = "owner_slot"
        const val SLOTS = "slots"
        const val FUNCTION_SLOT = "function_slot"
        const val LABEL = "label"
        const val SLOTTYPE = "slotType"
        const val CONTEXT = "context"
        const val CONTEXTFRAME = "context_frame"
        const val CONTEXTSLOT = "context_slot"
        const val EXPRESSION = "expression"
        const val PARTIALEXPRESSION = "partial_application"
    }

    fun isCompatible(type: String, packageName: String?) : Boolean {
        return ownerFrame == "${packageName}.${type}"
    }

}

/**
 * This allows us to separate the index logic from parsing logic.
 */
data class IndexBuilder(val dir: Directory, val lang: String) {
    val analyzer = LanguageAnalzyer.get(lang)
    val iwc = IndexWriterConfig(analyzer).apply{openMode = OpenMode.CREATE}
    val writer = IndexWriter(dir, iwc)

    fun index(doc: Document) {
        writer.addDocument(doc)
    }
    fun close() {
        writer.close()
    }
}


data class ExpressionSearcher(val agent: DUMeta) {
    val k: Int = 32
    private val maxFromSame: Int = 4
    private val analyzer = LanguageAnalzyer.get(agent.getLang())
    private val reader: DirectoryReader = DirectoryReader.open(buildIndex(agent))
    private val searcher = IndexSearcher(reader)
    private val slotSearch: Boolean = false

    data class ExpressionContext(val frame: String, val slot: String?)

    data class Expression(
            val owner: String,
            val context: ExpressionContext?,
            val functionSlot: String?,
            val label: String?,
            val utterance: String,
            val partialApplications: List<String>?,
            val bot: DUMeta) {

        fun toDoc() : Document {
            val expr = this
            val doc = Document()
            // Use the trigger based probes so that it works for multilingual.
            val probe = probeBuilder.invoke(expr)
            val slots = parseQualifiedSlotNames(expr.utterance)
            val expression = buildTypedExpression(expr.utterance, expr.owner, expr.bot)

            // Instead of embedding into expression, use StringField.
            val slotTypes = buildSlotTypes()
            for (slotType in slotTypes) {
                doc.add(StringField(ScoredDocument.SLOTTYPE, slotType, Field.Store.YES))
            }

            // "probe" is saved for retrieval and request intent model
            doc.add(TextField(ScoredDocument.PROBE, probe, Field.Store.YES))
            // "expression" is just for searching
            doc.add(TextField(ScoredDocument.EXPRESSION, expression, Field.Store.YES))
            doc.add(TextField(ScoredDocument.UTTERANCE, expr.utterance, Field.Store.YES))


            // We assume that expression will be retrieved based on the context.
            // this assume that there are different values for context:
            // default, active frame, active frame + requested slot.
            logger.info("context: ${buildFrameContext()}, expression: $expression, ${expr.utterance.lowercase(Locale.getDefault())}")
            doc.add(StringField(ScoredDocument.CONTEXT, buildFrameContext(), Field.Store.YES))
            val subFrameContext = buildSubFrameContext(bot)
            if (!subFrameContext.isNullOrEmpty()) {
                for (frame in subFrameContext) {
                    doc.add(StringField(ScoredDocument.CONTEXT, frame, Field.Store.YES))
                }
            }

            if (context?.slot != null) {
                logger.info("context slot ${context.slot}")
                doc.add(StringField(ScoredDocument.CONTEXTFRAME, context.frame, Field.Store.YES))
                doc.add(StringField(ScoredDocument.CONTEXTSLOT, context.slot, Field.Store.YES))
            }
            doc.add(StringField(ScoredDocument.OWNER, expr.owner, Field.Store.YES))
            doc.add(StringField(ScoredDocument.SLOTS, slots, Field.Store.YES))


            if (partialApplications != null) {
                logger.info("entailed slots: ${partialApplications.joinToString(",")}")
                for (entailedSlot in partialApplications) {
                    doc.add(StringField(ScoredDocument.PARTIALEXPRESSION, entailedSlot, Field.Store.YES))
                }
            }

            if (!expr.functionSlot.isNullOrEmpty())
                doc.add(StringField(ScoredDocument.FUNCTION_SLOT, expr.functionSlot, Field.Store.YES))
            if (!expr.label.isNullOrEmpty())
                doc.add(StringField(ScoredDocument.LABEL, expr.label, Field.Store.YES))
            return doc
        }

        /**
         * TODO: Currently, we only use the frame as context, we could consider to use frame and attribute.
         * This allows for tight control.
         */
        private fun buildFrameContext(): String {
            if (context != null) {
                return """{"frame_id":"${context.frame}"}"""
            } else {
                if (frameMap.containsKey(this.owner)) {
                    return """{"frame_id":"${frameMap[this.owner]}"}"""
                }
            }
            return "default"
        }

        private fun buildSubFrameContext(duMeta: DUMeta): List<String>? {
            if (context != null) {
                val subtypes = duMeta.getSubFrames(context.frame)
                if (subtypes.isNullOrEmpty()) return null
                return subtypes.map {"""{"frame_id":"$it"}"""}
            }
            return null
        }

        private fun buildSlotTypes(): List<String> {
            return DollarArgPatternRegex
                    .findAll(utterance)
                    .map { it.value.substring(1, it.value.length - 1) }
                    .map { bot.getSlotType(owner, it) }
                    .toList()
        }

    }

    val parser = QueryParser("expression", analyzer)

    /**
     * We assume each agent has its separate index.
     */
    fun search(rquery: String,
               expectations: DialogExpectations = DialogExpectations(),
               emap: MutableMap<String, MutableList<SpanInfo>>? = null): List<ScoredDocument> {
        if (rquery.isEmpty()) return listOf()

        var searchQuery = QueryParser.escape(rquery)


        logger.info("search with expression: $searchQuery")
        val query = parser.parse(searchQuery)


        // first build the expectation boolean it should be or query.
        // always add "default" for context filtering.
        val contextQueryBuilder = BooleanQuery.Builder()

        contextQueryBuilder.add(TermQuery(Term(ScoredDocument.CONTEXT, "default")), BooleanClause.Occur.SHOULD)
        if (expectations.activeFrames.isNotEmpty()) {
            for (expectation in expectations.getFrameContext()) {
                contextQueryBuilder.add(
                    TermQuery(Term(ScoredDocument.CONTEXT, expectation)),
                    BooleanClause.Occur.SHOULD
                )
                logger.info("search with context: $expectation")
            }
        }


        val queryBuilder = BooleanQuery.Builder()
        queryBuilder.add(query, BooleanClause.Occur.MUST)
        queryBuilder.add(contextQueryBuilder.build(), BooleanClause.Occur.MUST)

        // TODO(sean): Do we really need to search by slot type?
        // The introduction of entity type hierarchy makes this not as effective as it.
        if (emap != null && slotSearch) {
            val entityQueryBuilder = BooleanQuery.Builder()
            if (emap != null) {
                for (entityType in emap.keys) {
                    entityQueryBuilder.add(TermQuery(Term(ScoredDocument.SLOTTYPE, entityType)), BooleanClause.Occur.SHOULD)
                    logger.info("search with slotType: $entityType")
                }
            }
            queryBuilder.add(entityQueryBuilder.build(), BooleanClause.Occur.SHOULD)
        }
        
        val results = searcher.search(queryBuilder.build(), k).scoreDocs.toList()

        logger.info("got ${results.size} raw results for ${query}")

        if (results.isEmpty()) return emptyList()

        val res = ArrayList<ScoredDocument>()
        val keyCounts = mutableMapOf<String, Int>()
        val topScore = results[0].score
        for (result in results) {
            val doc = ScoredDocument(result.score / topScore, reader.document(result.doc))
            val count = keyCounts.getOrDefault(doc.ownerFrame, 0)
            keyCounts[doc.ownerFrame] = count + 1
            if (keyCounts[doc.ownerFrame]!! <= maxFromSame) {
                logger.info(doc.toString())
                res.add(doc)
            }
        }

        logger.info("got ${res.size} results for ${query}")
        return res
    }

    companion object {
        private val DollarArgPatternRegex = Pattern.compile("""\$(.+?)\$""").toRegex()
        val logger: Logger = LoggerFactory.getLogger(ExpressionSearcher::class.java)

        private val frameMap = mapOf(
            "io.opencui.core.confirmation.No" to "io.opencui.core.Confirmation",
            "io.opencui.core.confirmation.Yes" to "io.opencui.core.Confirmation",
            "io.opencui.core.hasMore.No" to "io.opencui.core.HasMore",
            "io.opencui.core.hasMore.Yes" to "io.opencui.core.HasMore",
            "io.opencui.core.booleanGate.No" to "io.opencui.core.BoolGate",
            "io.opencui.core.booleanGate.Yes" to "io.opencui.core.BoolGate",
        )


        // "I need $dish$" -> "I need [MASK]"
        // TODO(sean): this might be a good idea to try out.
        val maskParser = { expr: Expression ->
            DollarArgPatternRegex.split(expr.utterance).joinToString(separator = " [MASK] ")
        }

        // "I need $dish$" -> "I need < dish.trigger >"
        val angleSlotTriggerParser = { expr: Expression ->
            DollarArgPatternRegex.replace(expr.utterance)
            {
                val slotName = it.value.removeSurrounding("$")
                val triggers = expr.bot.getSlotMeta(expr.owner, slotName)?.triggers
                if (triggers.isNullOrEmpty()) {
                    slotName
                } else {
                    "< ${triggers[0]} >"
                }
            }
        }

        var probeBuilder: (Expression) -> String = angleSlotTriggerParser

        /**
         * We assume the slot names are in form of a.b.c.
         */
        @JvmStatic
        private fun parseQualifiedSlotNames(utterance: String): String {
            val res = DollarArgPatternRegex
                    .findAll(utterance)
                    .map { it.value.substring(1, it.value.length - 1) }   // remove leading and trailing $
                    .toList()
            return res.joinToString(",")
        }

        /**
         * This parses expression json file content into list of expressions, so that we
         * can index them one by one.
         */
        @JvmStatic
        private fun parseExpressions(exprOwners: JsonArray, bot: DUMeta): List<Expression> {
            val res = ArrayList<Expression>()
            for (owner in exprOwners) {
                owner as JsonObject
                val ownerId = getContent(owner["owner_id"])!!
                val expressions = owner["expressions"] ?: continue
                expressions as JsonArray
                for (expression in expressions) {
                    val exprObject = expression as JsonObject
                    val contextObject = exprObject["context"] as JsonObject?
                    val context = parseContext(contextObject)
                    val utterance = getContent(exprObject["utterance"])!!
                    val functionSlot = getContent(exprObject["function_slot"])
                    val partialApplicationsObject = exprObject["partial_application"] as JsonArray?
                    val partialApplications = parsePartialApplications(partialApplicationsObject)
                    val label = if (exprObject.containsKey("label")) getContent(exprObject["label"])!! else ""
                    res.add(Expression(ownerId, context, functionSlot, label, toLowerProperly(utterance), partialApplications, bot))
                }
            }
            return res
        }

        private fun parseContext(context: JsonObject?) : ExpressionContext? {
            if (context == null) return null
            val frame = getContent(context["frame_id"])!!
            val slot = getContent(context["slot_id"])
            return ExpressionContext(frame, slot)
        }

        private fun parsePartialApplications(context: JsonArray?) : List<String>? {
            if (context == null) return null
            val list = mutableListOf<String>()
            for (index in 0 until context.size()) {
                list.add(getContent(context.get(index))!!)
            }
            return list
        }

        /**
         * Currently, we append entity type to user utterance, so that we can retrieve back
         * the expression that contains both triggering and slot.
         * I think the better way of doing this is to use extra field. This way, we do not
         * have to parse things around.
         */
        @JvmStatic
        fun buildTypedExpression(utterance: String, owner: String, agent: DUMeta): String {
            return DollarArgPatternRegex.replace(utterance)
            {
                val slotName = it.value.removeSurrounding("$")
                var typeName = agent.getSlotType(owner, slotName)
                if (typeName.isEmpty()) typeName = "WrongName"
                "< $typeName >"
            }
        }

        // "My Phone is $PhoneNumber$" -> "my phone is $PhoneNumber$"
        fun toLowerProperly(utterance: String): String {
            val parts = utterance.split('$')
            var lowerCasedUtterace: String = ""
            for ((i, part) in parts.withIndex()) {
                if (i % 2 == 0) lowerCasedUtterace += part.lowercase(Locale.getDefault())
                else {
                    lowerCasedUtterace += '$'
                    lowerCasedUtterace += part
                    lowerCasedUtterace += '$'
                }
            }
            return lowerCasedUtterace
        }

        private fun getContent(primitive: JsonElement?): String? {
            return (primitive as JsonPrimitive?)?.content()
        }

        @JvmStatic
        fun buildIndex(agent: DUMeta): Directory {
            // we assume that index will put in the inputPath, we assume the version can be used to identify lang.
            val dirAsFile = File("./index/${agent.getOrg()}_${agent.getLabel()}_${agent.getLang()}_${agent.getBranch()}")

            // Use ram directory, not as safe, but should be faster as we reduced io.
            val dir = if (Dispatcher.memoryBased) {
                RAMDirectory()
            } else {
                val path = Paths.get(dirAsFile.absolutePath)
                logger.info("Dispatcher.deleteExistingIndex = ${Dispatcher.deleteExistingIndex}, dirExist = ${dirAsFile.exists()}")
                // Make sure we delete the past index for springboot so that at least we use the newer version
                // as we are rely on org/agent/version for uniqueness, which might fail.
                if (Dispatcher.deleteExistingIndex) dirAsFile.delete()
                MMapDirectory(path)
            }

            // Under springboot, we always build index, if directory does not exist, we also build index.
            if (Dispatcher.deleteExistingIndex || !dirAsFile.exists()) {
                val expressions = parseExpressions(agent.getFrameExpressions(), agent)
                logger.info("[ExpressionSearch] build index for ${agent.getLabel()}")
                val indexBuilder = IndexBuilder(dir, agent.getLang())
                expressions.map { indexBuilder.index(it.toDoc()) }
                indexBuilder.close()
            }
            return dir
        }
    }
}

