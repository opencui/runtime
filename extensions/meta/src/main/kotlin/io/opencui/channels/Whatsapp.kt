package io.opencui.channels

import io.opencui.core.*
import io.opencui.core.user.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import org.slf4j.Logger
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient

data class WhatsappMessage(val objectItem: String?, val entry: ArrayList<Entry>)

data class Metadata(val phoneNumberId: String?, val displayPhoneNumber: String?)

data class Profile(val name: String?)

data class Contacts(val profile: Profile?, val waId: String?)

data class WhatsappText(val body: String?)

data class Messages(
    val from: String?,
    val id: String?,
    val timestamp: String?,
    val type: String?,
    val text: WhatsappText?
)

data class Value(
    val messagingProduct: String?,
    val metadata: Metadata?,
    val contacts: ArrayList<Contacts>?,
    val messages: ArrayList<Messages>?
)

data class Changes(val field: String?, val value: Value?)

data class Entry(
    var id: String? = null,
    var changes: ArrayList<Changes> = arrayListOf()
)


/**
 * This is used to expose webhook for facebook whatsapp to call.
 * https://developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components
 */
@RestController
@RequestMapping("/org/{org}/bot/{agentId}/")
class WhatsappResources() {
    @GetMapping("c/whatsapp/v1/{channelId}/{lang}",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE])
	fun getResponse(
            @PathVariable("org") org: String,
            @PathVariable("agentId") agentId: String,
            @PathVariable("lang") lang: String,
            @PathVariable("channelId") channelId: String,
            @RequestParam("hub.mode") mode: String,
            @RequestParam("hub.verify_token") token: String,
            @RequestParam("hub.challenge") challenge: String): ResponseEntity<String> {
        logger.info("getting $token and $challenge for $mode")
		val botInfo = BotInfo(org, agentId, lang)
		val info = Dispatcher.getChatbot(botInfo).getConfiguration<IChannel>(channelId)
				?: return ResponseEntity("No longer active", HttpStatus.NOT_FOUND)
        logger.info("info = $info for $org:$agentId:$channelId:$token:$challenge")
		if (mode =="subscribe") {
            if (token != info[VERIFYTOKEN]) {
                logger.info("token mismatch...")
            } else {
                return ResponseEntity.ok(challenge)
            }
		}

		return ResponseEntity("Wrong Verify Token", HttpStatus.FORBIDDEN)
	}

	/*
     * The type of the attachment. Must be one of the following:image, video, audio, file
     * url:  URL of the file to upload. Max file size is 25MB (after encoding).
     * A Timeout is set to 75 sec for videos and 10 secs for every other file type.
     */

    @PostMapping("c/whatsapp/v1/{channelId}/{lang}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE])
	fun postResponse(
			@PathVariable("org") org: String,
			@PathVariable("agentId") agentId: String,
			@PathVariable("lang") lang: String,
			@PathVariable("channelId") channelId: String,
		    @RequestBody body: WhatsappMessage): ResponseEntity<String> {
		val botInfo = BotInfo(org, agentId, lang)
		Dispatcher.getChatbot(botInfo).getConfiguration<IChannel>(channelId)?: return ResponseEntity("NotFound", HttpStatus.NOT_FOUND)
        logger.info(body.toString())

        // There are a list of change in the message.
        if (body.entry.size != 0) {
			body.entry.forEach {
                logger.info(it.toString())
                // We only process the first message.
                for (change in it.changes) {
                    if (change.value?.messages == null) continue
                    for (message in change.value!!.messages!!) {

                        // For now, we only handle the text input message. Down the road
                        // we can use other api to handle other types.
                        if (message.type != "text") continue
                        val txt = message.text!!.body ?: continue
                        val from = message.from

                        // We make sure we mark this when we are done, so that we do not
                        // process twice.
                        // We can also try to mark the message to be seen.
                        // https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages
                        val msgId = message.id

                        val userInfo = UserInfo(whatsapp, from, channelId)
                        Dispatcher.process(userInfo, BotInfo(org, agentId, lang), textMessage(txt, msgId))
                    }
                }
            }
			return ResponseEntity.ok("EVENT_RECEIVED")
		} else {
			return ResponseEntity("not a page", HttpStatus.NOT_FOUND)
		}
	}


    companion object {
        const val CHANNELTYPE = "whatsapp"
        const val VERIFYTOKEN = "verify_token"
        const val SENDER = "sender"
        const val ID = "id"
        const val MESSAGE = "message"
        const val TEXT = "text"
        val logger: Logger = LoggerFactory.getLogger(WhatsappResources::class.java)
	    const val whatsapp = "whatsapp"
    }
}

class WhatsappChannel(override val info: Configuration) : IMessageChannel {
    private val channelLabel = info.label
    val client = WebClient.builder()
      .baseUrl("https://graph.facebook.com")
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build()

    val botId = info[BOTID]!!

    fun text(text: String) : Map<String, String> {
        return mapOf("text" to text)
    }
    inline fun <reified T> post(payload: T): String? {
        val request = client.post()
            .uri("/v14.0/$botId/messages")
            .header("Authorization", "Bearer ${info[ACCESSTOKEN]}")
            .body(Mono.just(payload), T::class.java)

        // val response = request.retrieve()bodyToMono(String::class.java)
        return request.exchange()
            .block()
            ?.bodyToMono(String::class.java)
            ?.block()
    }

    override fun getProfile(botInfo: BotInfo, psid: String): IUserIdentifier? {
        val request = client.get()
            .uri("/${psid}?fields=name,email,profile_pic&access_token=${info[ACCESSTOKEN]}")

        logger.info("send request: ${request.toString()}")

        val rres = request.retrieve()
            .bodyToMono(JsonObject::class.java)

        val res = rres.block()!!
        return UserInfo("whatsapp", psid, channelLabel).apply {
            this.name = res["name"].textValue()
            this.email = res["email"]?.textValue() ?: "$psid@${info.label}.whatsapp"
            this.phone = null
        }
    }

    override fun sendSimpleText(
        uid: String,
        rawMessage: TextPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        logger.info("$WHATSAPP:send ${rawMessage.text} to $uid")
        val payload = mapOf(
            "messaging_product" to "whatsapp",
            "to" to uid,
            "text" to mapOf("body" to rawMessage.text))

        val res = post(payload)

        logger.info(res.toString())
        return IChannel.Status(OK)
    }

    fun convertReply(r: Reply): Map<String, Any> {
        return mapOf(
            "type" to "reply",
            "reply" to mapOf("id" to r.payload, "title" to r.display))
    }
    fun convertCall(r: Call): Map<String, Any> {
        return mapOf(
            "type" to "reply",
            "reply" to mapOf("id" to r.payload, "title" to r.display))
    }

    fun convertClick(r: Click): Map<String, Any> {
        return mapOf(
            "type" to "reply",
            "reply" to mapOf("id" to r.payload, "title" to r.display))
    }

    fun getActions(insideActions: List<ClientAction>?, floatActions: List<Reply>?): List<Map<String, Any>> {
        val actions : List<ClientAction> = mutableListOf<ClientAction>().apply{
            if (insideActions != null) this.addAll(insideActions)
            if (floatActions != null) this.addAll(floatActions)
        }

        return actions.map{
            when(it) {
                is Reply -> convertReply(it)
                is Call -> convertCall(it)
                is Click -> convertClick(it)
            }
        }
    }

    fun actions(rawMessage: RichPayload): Map<String, Any> {
        return mapOf("buttons" to getActions(rawMessage.insideActions, rawMessage.floatActions))
    }

    override fun sendRichCard(
        uid: String,
        rawMessage: RichPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        logger.info("$WHATSAPP:send ${rawMessage.title} to $uid")
        val interactive = mapOf(
            "type" to "button",
            "header" to mapOf(
                "type" to  "image",
                "image" to mapOf("link" to rawMessage.richMedia?.fileUrl)),
            "body" to text(rawMessage.title),
            "footer" to text(rawMessage.description),
            "action" to actions(rawMessage)
        )

        val payload = mapOf(
            "messaging_product" to "whatsapp",
            "recipient_type" to "individual",
            "to" to uid,
            "type" to "interactive",
            "interactive" to interactive)

        logger.debug(payload.toString())
        val res = post(payload)

        logger.debug(res.toString())
        return IChannel.Status(OK)
    }

    override fun sendListText(
        uid: String,
        rawMessage: ListTextPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun sendListRichCards(
        uid: String,
        rawMessage: ListRichPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun sendRawPayload(
        uid: String,
        rawMessage: JsonObject,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun markSeen(uid: String, botInfo: BotInfo, messageId: String?) {
        if (messageId == null) return
        // make sure we use phone number for uid.
        val payload = mapOf(
            "messaging_product" to "whatsapp",
            "status" to "read",
            "message_id" to "$messageId"
        )
        val res = post(payload)
        logger.debug("mark seen response: ${res.toString()}")
    }

    companion object : ExtensionBuilder<IChannel> {
        val logger = LoggerFactory.getLogger(WhatsappChannel::class.java)
        const val ACCESSTOKEN = "access_token"
        const val MARKSEEN = "mark_seen"
        const val TYPEON = "typing_on"
        const val WHATSAPP = "whatsapp"
        const val BOTID = "phone_number_id"
        const val OK = "ok"
        override fun invoke(config: Configuration): IChannel {
            return WhatsappChannel(config)
        }
    }
}