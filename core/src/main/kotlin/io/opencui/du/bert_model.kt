package io.opencui.du


import io.opencui.core.RuntimeConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * This data structure is used for capturing the response from slot model.
 */
data class SlotPrediction(val result: UnifiedModelResult, val index: Int) {
    val segments: List<String> = result.segments
    val classLogit: Float = result.classLogits[3*index + 1]
    val startLogits: List<Float> = result.startLogitss[index]
    val endLogits: List<Float> = result.endLogitss[index]
    val segStarts: List<Long> = result.segStarts
    val segEnds: List<Long> = result.segEnds
}

data class UnifiedModelResult(
        // The tokens used by the model on the server side.
        val segments: List<String>,
        // Eor each slot, the probability of user: whether mentioned a value, or do not care about the slot.
        val classLogits: List<Float>,

        // The probability of the starting position (in tokens) of the value for the slot value questions.
        val startLogitss: List<List<Float>>,
        // The probability of the ending position (in tokens) of the value for the slot value questions.
        val endLogitss: List<List<Float>>,

        // The starting character position for each token.
        val segStarts: List<Long>,
        // The ending character position for each token.
        val segEnds: List<Long>) {
    operator fun get(index:Int) : SlotPrediction { return SlotPrediction(this, index)}
}


/**
 * For now, we only use probability of the utterance means the same as probe.
 * In the future, when we start to deal with multiple intents in one utterance,
 * the span host the start and end of the utterance
 * that matches the probe.
 */
data class IntentPrediction(val prob: Float)

data class IntentModelResult(val probs: List<Float>) {
    val size = probs.size/2
    operator fun get(index: Int): IntentPrediction {
        check(2*index+1 < probs.size)
        return IntentPrediction(probs[2*index+1])
    }
}

data class ClassificationResults(
    val probs: List<Float>) {
    val size: Int = probs.size
}


/**
 * This is api that current nlu model provide. But we should expose more kotlin friendly
 * returns so that it is easy to provide implementation from other framework.
 *
 */
interface NLUModel {
    fun shutdown() {}

    /**
     * Given a user [utterance] in specified [language] and a list of expression [exemplars],
     * @returns the probability of the user utterance have the same meaning of each of exemplars.
     */
    fun predictIntent(lang:String, utterance: String, exemplars: List<String>) :  IntentModelResult?


    /**
     * Given a user [utterance] in specified [language] and a list of slot value probes
     * @return the result that include tokenization used by model, yes/no/dontcare of the value for
     * each slot, and proability of start and end of the value. Notice this only handles the single
     * value use case.
     */
    fun predictSlot(lang:String, utterance: String, probes: List<String>): UnifiedModelResult
}


/**
 * This host all the string literals used by
 */
object TfBertNLUModel {
    const val unifiedLogitsStr = "class_logits"
    const val startLogitsStr = "start_logits"
    const val endLogitsStr = "end_logits"
    const val segmentsStr = "segments"
    const val segStartStr = "pos_starts"
    const val segEndStr = "pos_ends"

    const val classificationProbs = "probs"

    const val outputs = "outputs"
}

object PtBertNLUModel {
    const val classLogitsStr = "classLogits"
    const val startLogitsStr = "startLogits"
    const val endLogitsStr = "endLogits"
    const val segmentsStr = "segments"
    const val segStartStr = "segStarts"
    const val segEndStr = "segEnds"

    const val intentProbs = "probs"

    const val outputs = "outputs"
}


/**
 * Dialog state tracker takes natural language user utterance, and convert that into frame event
 * based on conversation history.
 *
 * For now, this functionality is seperated into two levels, lower level nlu where context is not
 * taking into consideration, high level that use the output from low lever api and conversational
 * history to finish the conversion in context dependent way.
 *
 * We will have potentially different lower level apis, for now, we assume the bert based on api
 * which is defined per document. We assume there are two models (intents and slots) for now, and
 * their apis is defined as the corresponding document.
 *
 * For tensorflow, we can serve in model grpc/rest automatically.
 * https://www.tensorflow.org/tfx/serving/api_rest
 *
 * By using restful instead grpc, we can remove another piece of dependency that may be hurting
 * quarkus native potentially.
 */
data class TfRestBertNLUModel(val modelVersion: Long = 1) : NLUModel {
    data class TfRestPayload(val utterance: String, val probes: List<String>)
    data class TfRestRequest(val signature_name: String, val inputs: TfRestPayload)
    val config: Triple<String, Int, String> = RuntimeConfig.get(TfRestBertNLUModel::class)
    val client: HttpClient = HttpClient.newHttpClient()
    val url: String = "${config.third}://${config.first}:${config.second}"
    val timeout: Long = 2000


    fun parse(modelName: String, signatureName: String, utterance: String, probes: List<String>) : JsonObject? {
        val payload = TfRestPayload(utterance, probes)
        val input = TfRestRequest(signatureName, payload)
        logger.debug("connecting to $url/v1/models/${modelName}:predict")
        logger.debug("utterance = $utterance and probes = $probes")
        val request: HttpRequest = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(input)))
            .uri(URI.create("$url/v1/models/${modelName}:predict"))
            .timeout(Duration.ofMillis(timeout))
            .build()

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) {
            val body = response.body()
            Json.parseToJsonElement(body).get(TfBertNLUModel.outputs) as JsonObject
        } else {
            // We should not be here.
            logger.error("NLU request error: ${response.toString()}")
            null
        }
    }

    override fun shutdown() { }


    override fun predictIntent(lang: String, utterance: String, exemplars: List<String>): IntentModelResult? {
        val outputs = parse("${lang}_intent", "intent", utterance, exemplars)!!
        val classLogits = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(TfBertNLUModel.unifiedLogitsStr)).flatten()
        return IntentModelResult(classLogits)
    }

    override fun predictSlot(lang: String, utterance: String, probes: List<String>): UnifiedModelResult {
        val outputs = parse("${lang}_slot", "slot", utterance, probes)!!
        val segments = Json.decodeFromJsonElement<List<List<String>>>(outputs.get(TfBertNLUModel.segmentsStr)).flatten()
        val startLogitss = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(TfBertNLUModel.startLogitsStr))
        val endLogitss = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(TfBertNLUModel.endLogitsStr))
        val classLogits = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(TfBertNLUModel.unifiedLogitsStr)).flatten()
        val segStarts = Json.decodeFromJsonElement<List<List<Long>>>(outputs.get(TfBertNLUModel.segStartStr)).flatten()
        val segEnds = Json.decodeFromJsonElement<List<List<Long>>>(outputs.get(TfBertNLUModel.segEndStr)).flatten()
        return UnifiedModelResult(segments, classLogits, startLogitss, endLogitss, segStarts, segEnds)
    }

    companion object {
        val logger = LoggerFactory.getLogger(TfRestBertNLUModel::class.java)
    }
}





data class PtRestBertNLUModel(val modelVersion: Long = 1) : NLUModel {
    data class PtRestPayload(val utterance: String, val probes: List<String>)
    data class PtRestRequest(val signature_name: String, val inputs: PtRestPayload)
    data class PtInput(val utterance: String, val probes: String)
    val config: Pair<String, Int> = RuntimeConfig.get(PtRestBertNLUModel::class)
    val client: HttpClient = HttpClient.newHttpClient()
    val url: String  = "http://${config.first}:${config.second}"
    val timeout: Long = 2000

    fun parse(modelName: String, signatureName: String, utterance: String, probes: List<String>) : JsonObject? {
        val payload = PtRestPayload(utterance, probes)
        val input = PtRestRequest(signatureName, payload)

        val request: HttpRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(input)))
                .uri(URI.create("$url/predictions/${modelName}"))
                .timeout(Duration.ofMillis(timeout))
                .build()

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())

        return if (response.statusCode() == 200) {
            Json.parseToJsonElement(response.body()).get(PtBertNLUModel.outputs) as JsonObject
        } else {
            null
        }
    }

    override fun shutdown() { }


    override fun predictIntent(lang: String, utterance: String, exemplars: List<String>): IntentModelResult? {
        val outputs = parse("${lang}_intent", "intent", utterance, exemplars)!!
        val fprobs = Json.decodeFromJsonElement<List<Float>>(outputs.get(PtBertNLUModel.intentProbs))
        return IntentModelResult(fprobs)
    }

    override fun predictSlot(lang: String, utterance: String, probes: List<String>): UnifiedModelResult {
        val outputs = parse("${lang}_slot", "slot", utterance, probes)!!
        val segments = Json.decodeFromJsonElement<List<String>>(outputs.get(PtBertNLUModel.segmentsStr))
        val startLogitss = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(PtBertNLUModel.startLogitsStr))
        val endLogitss = Json.decodeFromJsonElement<List<List<Float>>>(outputs.get(PtBertNLUModel.endLogitsStr))
        val classLogits = Json.decodeFromJsonElement<List<Float>>(outputs.get(PtBertNLUModel.classLogitsStr))
        val segStarts = Json.decodeFromJsonElement<List<Long>>(outputs.get(PtBertNLUModel.segStartStr))
        val segEnds = Json.decodeFromJsonElement<List<Long>>(outputs.get(PtBertNLUModel.segEndStr))
        return UnifiedModelResult(segments, classLogits, startLogitss, endLogitss, segStarts, segEnds)
    }
}