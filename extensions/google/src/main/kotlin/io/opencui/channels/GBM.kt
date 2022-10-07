package io.opencui.channels

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.businessmessages.v1.Businessmessages
import com.google.api.services.businessmessages.v1.model.*
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import io.opencui.core.*
import io.opencui.core.user.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

enum class GBMEventType {
  TYPING_STARTED,
  TYPING_STOPPED,
  REPRESENTATIVE_JOINED,
  REPRESENTATIVE_LEFT
}


/**
 * The document for connecting to Google business messages (GBM)  as channel.
 * https://developers.google.com/business-communications/business-messages/guides
 * https://developers.google.com/business-communications/business-messages/guides/how-to/message/message-lifecycle
 */
@RestController
@RequestMapping("/org/{org}/bot/{agentId}/")
class GBMResource {
    @PostMapping("c/gbm/v1/{label}/{lang}",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE])
	fun postResponse(
        @PathVariable org: String,
        @PathVariable agentId: String,
        @PathVariable lang: String,
        @PathVariable label: String,
        @RequestBody bodyStr: String): ResponseEntity<JsonElement> {
        val body = Json.parseToJsonElement(URLDecoder.decode(bodyStr, Charsets.UTF_8)) as ObjectNode
        val botInfo = BotInfo(org, agentId, lang)
        logger.info("got body: $body")
        val info = Dispatcher.getChatbot(botInfo).getConfiguration<IChannel>(label)
        if (info == null) {
            logger.info("could not find configure for $CHANNELTYPE/$label")
            return ResponseEntity(Json.makePrimitive("No longer active"), HttpStatus.NOT_FOUND)
        }

        // This is needed for get verified.
        if (body.containsKey(SECRET)) {
            val secret = body.getString(SECRET)
            val stoken = body.getString(CLIENTTOKEN)
            val ttoken =  info[CLIENTTOKEN]
            return if (stoken == ttoken) {
                ResponseEntity.ok(Json.encodeToJsonElement(mapOf("secret" to secret)))
            } else {
                ResponseEntity(Json.makePrimitive("verify failed"), HttpStatus.NOT_FOUND)
            }
        }

        val conversationId = body.getString(CONVERSATIONID)
        if (body.containsKey(REQUESTID)) {
            val requestId = body.getString(REQUESTID)
            
            var utterance: String? = when {
                body.containsKey(MESSAGE) -> {
                    val msgJson = body[MESSAGE]!! as JsonObject
                    if (msgJson.has(TEXT)) {
                        msgJson.getString(TEXT)
                    } else {
                        null
                    }
                }
                body.containsKey(SUGGESTION) -> {
                    val msgJson = body[SUGGESTION]!! as JsonObject
                    if (msgJson.has(TEXT)) {
                        msgJson.getString(TEXT)
                    } else {
                        null
                    }
                }
                else -> null
            }

            // always forward to dispatcher so that they can decide what to do.
            if (utterance != null) {
                // Before we process incoming message, we need to create user session.
                val userInfo = UserInfo(CHANNELTYPE, conversationId, label)
                var userSession = Dispatcher.getUserSession(userInfo, botInfo)
                if (userSession == null) {
                    userSession = Dispatcher.createUserSession(userInfo, botInfo)
                }
                Dispatcher.getReply(userSession, textMessage(utterance, requestId))
            } else {
                return ResponseEntity(Json.makePrimitive("not $MESSAGE nor $SUGGESTION"), HttpStatus.BAD_REQUEST)
            }
        } else {
            logger.info("no $REQUESTID found in $body.")
        }

        return ResponseEntity(Json.makePrimitive("EVENT_RECEIVED"), HttpStatus.OK)
	}

    companion object {
        const val CHANNELTYPE = "gbm"
        const val CONVERSATIONID = "conversationId"
        const val REQUESTID = "requestId"
        const val MESSAGE = "message"
        const val CLIENTTOKEN = "clientToken"
        const val SECRET = "secret"
        const val SUGGESTION = "suggestionResponse"
        const val TYPE = "type"
        const val TEXT = "text"
        val decoder: Base64.Decoder = Base64.getDecoder()
        val logger = LoggerFactory.getLogger(GBMResource::class.java)
    }
}

/**
 * Channel is bot/app dependent concept.
 * There are three regions for RBM.
 */
data class GBMChannel(override val info: Configuration) : IMessageChannel {
    private var credential: GoogleCredentials? = null
    // Reference to the BM api builder
    private var builder: Businessmessages.Builder? = null

    init {
        // We do need to initialize the api.
        logger.info("initing based on $info")
        initBmApi(info)
    }

    private fun convertClientActions(clientActions: List<ClientAction>?) : List<BusinessMessagesSuggestion>? {
        return clientActions?.map{
            when (it) {
                is Reply -> BusinessMessagesSuggestion()
                    .setReply(
                        BusinessMessagesSuggestedReply().setText(it.display).setPostbackData(it.payload)
                    )

                is Click -> BusinessMessagesSuggestion()
                    .setAction(
                        BusinessMessagesSuggestedAction().setText(it.display).setPostbackData(it.payload)
                            .setOpenUrlAction(BusinessMessagesOpenUrlAction().setUrl(it.url))
                    )

                is Call -> BusinessMessagesSuggestion()
                    .setAction(
                        BusinessMessagesSuggestedAction().setText(it.display).setPostbackData(it.payload)
                            .setDialAction(BusinessMessagesDialAction().setPhoneNumber(it.phoneNumber))
                    )
            }
        }
    }

    private fun convertMedia(media: RichMedia) : BusinessMessagesMedia {
         return BusinessMessagesMedia()
             .setHeight(media.height.toString())
             .setContentInfo(BusinessMessagesContentInfo().setFileUrl(media.fileUrl))
    }

    override fun sendSimpleText(
        uid: String,
        rawMessage: TextPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        sendResponse(
            BusinessMessagesMessage()
                .setMessageId(UUID.randomUUID().toString())
                .setText(rawMessage.text)
                .setRepresentative(getRepresentative()),
            uid)
        return IChannel.Status("ok")
    }

    override fun sendRichCard(
        uid: String,
        rawMessage: RichPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        val insideActions = convertClientActions(rawMessage.insideActions)
        val floatActions = convertClientActions(rawMessage.floatActions)

        // first create the Bus
        val standaloneCard: BusinessMessagesStandaloneCard = BusinessMessagesStandaloneCard()
            .setCardContent(
                BusinessMessagesCardContent()
                    .setTitle(rawMessage.title)
                    .setDescription(rawMessage.description)
                    .setSuggestions(insideActions)
                    .apply {
                        if (rawMessage.richMedia != null) {
                            setMedia(convertMedia(rawMessage.richMedia!!))
                        }
                    })

       val fallbackText = StringBuilder()
           .append(rawMessage.title).append("\n\n")
           .append(rawMessage.description).append("\n\n")
           .apply{
               if (rawMessage.richMedia != null) append(rawMessage.richMedia!!.fileUrl)
           }

       sendResponse(
            BusinessMessagesMessage()
                .setMessageId(UUID.randomUUID().toString())
                .setRichCard(
                    BusinessMessagesRichCard()
                        .setStandaloneCard(standaloneCard))
                .setRepresentative(getRepresentative())
                .setFallback(fallbackText.toString())
                .setSuggestions(floatActions),
           uid)

       return IChannel.Status("ok")
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

    override fun typing(uid: String, botInfo: BotInfo) {
        // sendIsTypingMessage(id)
    }

    override fun getProfile(botInfo: BotInfo, id: String): IUserIdentifier? {
        TODO("Not yet implemented")
    }

    /**
     * Posts a message to the Business Messages API, first sending a typing indicator event and
     * sending a stop typing event after the message has been sent.
     *
     * @param message The message object to send the user.
     * @param conversationId The conversation ID that uniquely maps to the user and agent.
     */
    private fun sendResponse(message: BusinessMessagesMessage, conversationId: String) {
        try {
            // Send typing indicator
            var event: BusinessMessagesEvent = BusinessMessagesEvent()
                .setEventType(GBMEventType.TYPING_STARTED.toString())
            var request: Businessmessages.Conversations.Events.Create = builder!!.build().conversations().events()
                .create("conversations/$conversationId", event)
            request.eventId = UUID.randomUUID().toString()
            request.execute()
            logger.info("message id: " + message.getMessageId())
            logger.info("message body: " + message.toPrettyString())

            // Send the message
            val messageRequest: Businessmessages.Conversations.Messages.Create =
                builder!!.build().conversations().messages()
                    .create("conversations/$conversationId", message)

            // Setup retries with exponential backoff
            val httpRequest: HttpRequest = (messageRequest as AbstractGoogleClientRequest<*>).buildHttpRequest()
            httpRequest.unsuccessfulResponseHandler = HttpBackOffUnsuccessfulResponseHandler(
                ExponentialBackOff()
            )
            httpRequest.execute()

            // Stop typing indicator
            event = BusinessMessagesEvent()
                .setEventType(GBMEventType.TYPING_STOPPED.toString())
            request = builder!!.build().conversations().events()
                .create("conversations/$conversationId", event)
            request.eventId = UUID.randomUUID().toString()
            request.execute()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, EXCEPTION_WAS_THROWN, e)
        }
    }

    /**
    * Initializes credentials used by the Business Messages API.
    */
    private fun initCredentials(info : Configuration) {
        logger.info("Initializing credentials for Business Messages.")
        try {
            val token = info[CREDENTIAL]!! as String
            credential = GoogleCredentials.fromStream(token.byteInputStream())
            credential = credential!!.createScoped(
                Arrays.asList(
                    "https://www.googleapis.com/auth/businessmessages"
                )
            )
        } catch (e: Exception) {
            logger.log(Level.SEVERE, EXCEPTION_WAS_THROWN, e)
        }
    }

    /**
     * Initializes the BM API object.
     */
    private fun initBmApi(info: Configuration) {
        logger.info("Initializing Business Messages API")
        if (credential == null) {
            initCredentials(info)
        }

        try {
            val httpTransport: HttpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = JacksonFactory.getDefaultInstance()

            // create instance of the BM API
            builder = Businessmessages.Builder(httpTransport, jsonFactory, null)
                .setApplicationName("Framely Bot")

            // set the API credentials and endpoint
            builder!!.setHttpRequestInitializer(HttpCredentialsAdapter(credential))
        } catch (e: Exception) {
            logger.log(Level.SEVERE, EXCEPTION_WAS_THROWN, e)
        }
    }

    private  fun getRepresentative() : BusinessMessagesRepresentative {
        return BusinessMessagesRepresentative()
            .setRepresentativeType("Bot")
            .setDisplayName("Framely Bot")
            .setAvatarImage("https://storage.googleapis.com/sample-avatars-for-bm/bot-avatar.jpg");
    }

    companion object : ExtensionBuilder<IChannel> {
        private val logger = Logger.getLogger(GBMChannel::class.java.name)
        private const val EXCEPTION_WAS_THROWN = "an exception was thrown"
        private const val CLIETTOKEN = "client_token"
        private const val CREDENTIAL = "credential"

        val channelType = "gbm"

        override fun invoke(config: Configuration): IChannel {
            return GBMChannel(config)
        }
    }
}