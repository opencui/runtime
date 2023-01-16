package io.opencui.supports

import com.fasterxml.jackson.databind.ObjectMapper
import io.opencui.core.*
import io.opencui.core.user.*
import io.opencui.serialization.*
import io.opencui.support.ISupport
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import java.net.URLEncoder
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono


/**
 * We are closely following the doc here
 * https://www.chatwoot.com/docs/product/channels/api/create-channel
 *
 * To make the handoff work:
 * 1. we should have a bot as user in the system that is never login.
 * 2. then we should have at least one team with real users that can answer the question with bot user is not in it.
 *
 * After we create conversation,
 * 1. we should assign it to bot user.
 * 2. when the handoff is triggered, we reassign to a team.
 * 3. If we ever support handback, then we do reassign again.
 *
 */


/**
 * As support, this Chatwoot support receives the agent/bot reply and all we need to do it
 * forward payload to corresponding channel defined on our side. For now, we are moving in
 * the container direction.
 *
 * When we get this message, we need to forward the message to end user.
 * For this, we need to figure out which channel does this message is intended for.
 *
 */
@RestController
@RequestMapping("/org/{org}/bot/{agentId}/")
class ChatwootResource {
    @PostMapping("s/chatwoot/v1/",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE])
    fun postResponse(
        @PathVariable("org") org: String,
        @PathVariable("agentId") agentId: String,
        body: JsonObject
    ): ResponseEntity<JsonElement>  {
        logger.info(body.toPrettyString())

        // lang is saved in the attribute.
        val event = body["event"].asText()
        var attributes: JsonObject? = null
        var conversationId: Int? = null
        when {
            event.startsWith("message") -> {
                attributes = body.getObject(listOf("conversation", "additional_attributes"))
                conversationId = body["conversation"]["id"].asInt()
            }
            event.startsWith("conversation") -> {
                attributes = body.getObject("additional_attributes")
            }
            else -> {
                logger.info("we should not be here with $event")
            }
        }

        val lang = attributes!!["lang"]!!.asText()

        val botInfo = BotInfo(org, agentId, lang)

        val bot = Dispatcher.getChatbot(botInfo)
        if (bot == null) {
            logger.error("Can not find the bot ${botInfo}")
        }

        val support = Dispatcher.getSupport(botInfo) as ChatwootSupport

        // there are three possible solutions when it comes to remember :
        // user, user/channel, user/channel/session
        // we can save the info in the source_id, or additional attribute of the conversation.

        val channelType = attributes["channelType"]!!.asText()
        val channelId = attributes["channelLabel"]!!.asText()
        val userId = attributes["userId"]!!.asText()
        val user = UserInfo(channelType, userId, channelId)
        val userSession = Dispatcher.getUserSession(user, botInfo)
        if(userSession!=null){
            logger.info("conversationID: ${userSession["CONVERSATIONID"].toString()}")
        }

        if(userSession == null || (conversationId!=null && userSession["CONVERSATIONID"] != conversationId)){
            logger.info("session has been closed, conversation is resolved")
            return ResponseEntity.ok(Json.makePrimitive("EVENT_RECEIVED"))
        }

        if(event=="conversation_resolved"){
            Dispatcher.closeSession(user, botInfo)
            return ResponseEntity.ok(Json.makePrimitive("EVENT_RECEIVED"))
        }
        val private = body["private"].asBoolean()
        val conversation_status = body["conversation"]["status"].asText()
        // First let's not resend incoming message and bot message.
        if(!body.has("message_type")) return ResponseEntity.ok(Json.makePrimitive("EVENT_RECEIVED"))
        val msgType = body["message_type"]!!.asText()
        val senderId = (body["sender"] as JsonObject)["id"]!!.intValue()
        // only forward outgoing messages from live agent (instead of bot).
        if (event == "message_created" && msgType == "outgoing" && conversation_status == "open" && senderId != support.botId && !private) {
            val msg = body["content"].asText("")
            // Simply forward the message.
            Dispatcher.send(user, botInfo, listOf(msg))
        }
        return ResponseEntity.ok(Json.makePrimitive("EVENT_RECEIVED"))
    }

    companion object {
        val logger = LoggerFactory.getLogger(ChatwootResource::class.java)
    }
}


/**
 * Chatwoot as embedded support.
 */
data class ChatwootSupport(override val info: Configuration) : ISupport {

    data class CreateContactRequest(
        val inbox_id: Int,
        val name: String,
        val email: String,
        val phone_number: String,
        val additional_attributes: Map<String, Any> = emptyMap())


    data class CreateConversationRequest(
        val source_id: String?=null,
        val inbox_id: Int,
        val contact_id: Int,
        val additional_attributes: Map<String, String> = emptyMap(),
        val status: String = "bot")


    data class CreateConversationResponse(
        val id: Int,
        val account_id: Int,
        val inbox_id: Int
    )

    data class CreateMessageRequest(
        val content: String,
        val message_type: String = "incoming",
        val private: Boolean = false,
        val content_type: String = "text",
        val content_attributes: Map<String, Any> = mutableMapOf())
    init{
        val mapper = ObjectMapper()
        logger.info("info: " + mapper.writeValueAsString(info))
    }
    val hostport: String = info[ENDPOINT]!! as String
    val account_id: String = info[ORGID]!! as String
    val userKey: String = info[TOKEN]!! as String
    val teamId: Int? = if(info.containsKey(TEAMID)) info[TEAMID]!! as Int else null
    val botId: Int = info[BOTID]!! as Int
    val inbox: Int = info[INBOX]!! as Int

    override fun toString(): String { return "chatwoot at $hostport:$account_id:$inbox" }

    val client = WebClient.builder()
      .baseUrl(hostport)
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build()

    inline fun <reified T> post(payload: T, path: String): JsonObject? {
        val response = client.post()
            .uri(path)
            .header("api_access_token", userKey)
            .body(Mono.just(payload), T::class.java)
            .retrieve()
            .bodyToMono(JsonObject::class.java)
        return response.block()
    }

    override fun name(): String { return "Chatwoot" }
    override fun isInitiated(session: UserSession): Boolean {
        return session.containsKey(CONVERSATIONID)
    }

    /**
     * The key feature the support system need to support is it should have the notion of user.
     * The chatbot mainly operates on the short session where each session are started by the end user or
     * bot, with some predefined goals, and as soon as the current goals are done, the session is over.
     *
     * We introduce the concept of user/profile, each profile corresponding to an end user, can have
     * many short session with the system (chatbot + live agent). Here we assume that when bot are
     * These sessions can come from different channels. Profile
     * is identified by email and phone number, and also contains name and avatar for better visual.
     *
     * On chatwoot, the contact corresponds to user, user corresponds to live agent.
     *
     * Also, it is possible that session can time, but the task is not done yet. For that, we need to
     * keep the unfinished service on the stack, so that we can continue to finish them.
     *
     * One might need to access services in more than one session, and for that we need to make sure
     * the context can be accessed across sessions. The easiest way of supporting that is to create
     * yield action, where we can close the session, but keep the context for further processing.
     *
     * Or, we can save the session to user, and then we can keep all the histories automatically.
     */
    override fun initSession(session: UserSession) {
        logger.info("getting contact id...")

        // first we need to test whether user is there, and get id if we need to create user.
        //source_id need to get from createContact's response
        lateinit var source_id: String
        val profile = session.userIdentifier as UserInfo
        val responseJson = createContact(profile!!, profile)
        val contactId = if (responseJson != null) {
            println("!!!!! ${responseJson.toPrettyString()}")
            val res = responseJson.get("contact").get("id").intValue()
            source_id = responseJson.get("contact_inbox").get("source_id").asText()
            println("contact id = $res")
            res
        } else {
            val searchResponseJson = searchContact(profile)!!
            val payloads = searchResponseJson["payload"] as JsonArray
            if (payloads.size() != 1) {
                logger.warn("No singleton return for contact search for ${profile as IUserIdentifier}")
            }
            if (payloads.size() > 0) {
                val lcontactId = payloads[0]["id"].intValue()
                source_id = payloads[0]["contact_inboxes"][0]["source_id"].asText()
                logger.info("the first contact has the contactId: $lcontactId")
                // now we need to update the additional attribute.
                updateContact(lcontactId, profile, profile)
                lcontactId
            } else {
                null
            }
        }

        if (contactId != null) {
            // then we create conversation and save conversation ID in the user session.
            // To make sure that we do not have name space conflicting.
            val userId = profile.uuid()
            val conversationJson = createConversation(session.botInfo.lang, inbox, contactId.toInt(), source_id, profile)

            val conversation = if (conversationJson != null) {
                Json.decodeFromJsonElement<CreateConversationResponse>(conversationJson)
            } else {
                Json.decodeFromJsonElement<CreateConversationResponse>(Json.parseToJsonElement("{}"))
            }
            logger.info("created new conversation with id ${conversation.id}")

            // chat should go into bot, so that live agent doesn't get bothered yet.
            assign(conversation.id, botId, null)
            session.put(CONVERSATIONID, conversation.id)
        }
    }

    fun createContact(contact: IUserProfile, payload: Map<String, Any>) : JsonObject? {
        val endpoint = "/api/v1/accounts/$account_id/contacts"
        var phone = ""
        if(contact.phone != null && !contact.phone!!.value.isNullOrEmpty()) phone = if(contact.phone!!.value.first()=='+') contact.phone!!.value else "+${contact.phone!!.value}"
        val contactReq = CreateContactRequest(
            inbox,
            contact.name?:"",
            contact.uuid()?:"",
            phone,
            additional_attributes = payload)
        logger.info("create contact for ${contactReq}")
        val rres = post(contactReq, endpoint) ?: return null
        return rres.get("payload") as JsonObject
    }

    fun searchContact(contact: IUserProfile) : JsonObject? {
        // We assume the email or phone number are unique identifier.
        // It is builder channel implementer's job to make sure these are either unique or assume identity.
        val endpoint = if (!contact.uuid().isNullOrEmpty()) {
            "/api/v1/accounts/$account_id/contacts/search?q=${contact.uuid()!!}&sort=email"
        } else {
            var phone = URLEncoder.encode(contact.phone!!.value, "UTF-8")
            if(phone.first()=='+') phone = phone.substring(1, phone.length)
            "/api/v1/accounts/$account_id/contacts/search?q=$phone&sort=phone_number"
        }
        logger.info("search $contact from $endpoint")
        return client
            .get()
            .uri(endpoint)
            .header("api_access_token", userKey)
            .retrieve()
            .bodyToMono(JsonObject::class.java).block()
    }

    private fun updateContact(id: Int, contact: IUserProfile, payload: Map<String, Any>) {
        val endpoint = "/api/v1/accounts/$account_id/contacts/$id"
        val phone = if(contact.phone != null && contact.phone!!.value.isNullOrEmpty()) contact.phone!!.value else ""
        val contactReq = CreateContactRequest(
            inbox,
            contact.name?:"",
            contact.uuid()?:"",
            phone,
            additional_attributes = payload)
        logger.info("update contact for ${contactReq}")
        client.put()
            .uri(endpoint)
            .header("api_access_token", userKey)
            .retrieve()
            .bodyToMono(JsonObject::class.java)
            .block()
    }


    fun createConversation(
        lang: String,
        inbox_id: Int,
        contact_id: Int,
        source_id: String? = null,
        extras: Map<String, Any> = emptyMap()) : JsonObject? {
        val endpoint = "/api/v1/accounts/$account_id/conversations"
        val userInfo = extras as UserInfo
        val conversationReq = CreateConversationRequest(source_id, inbox_id, contact_id,
            mapOf("channelType" to userInfo.channelType!!, "channelLabel" to userInfo.channelLabel!!,"userId" to userInfo.userId!!, "lang" to lang))
        logger.info("ccreateConversation $conversationReq")
        return post(conversationReq, endpoint)
    }


    private fun toggleStatus(conversation_id: Int, status: String = "bot") {
        val endpoint = "/api/v1/accounts/$account_id/conversations/$conversation_id/toggle_status"
        val status = mapOf("status" to status)
        post(status, endpoint)
    }

    private fun assign(conversation_id: Int, assignee: Int?, team: Int?) {
        val endpoint = "/api/v1/accounts/$account_id/conversations/$conversation_id/assignments"
        if (assignee == null && team == null) throw RuntimeException("Assignee and team can not both be null")
        val status = if (assignee != null) {
            mapOf("assignee_id" to assignee)
        } else {
            mapOf("team_id" to team)
        }
        post(status, endpoint)
    }

    private fun getOnlineAgent(): String? {
        val endpoint = "/api/v1/accounts/$account_id/teams/$teamId/team_members"
        val rres = client.get().uri(endpoint)
            .header("api_access_token", userKey)
            .retrieve()
            .bodyToMono(JsonArray::class.java)
            .block() ?: return null

        for(json in rres) {
            if (json["availability_status"].asText() == "online") {
                return json["id"].asText()
            }
        }

        return null
    }
    private fun sendMessage(conversation_id: Int, messageReq: CreateMessageRequest) : JsonObject? {
        val endpoint = "/api/v1/accounts/$account_id/conversations/$conversation_id/messages"
        return post(messageReq, endpoint)
    }

    private fun sendMessage(session: UserSession, content: TextPayload, direction: String) {
        // here we need to create the outgoing message.
        val conversation_id = session.get(CONVERSATIONID) as Int?
        lateinit var message: CreateMessageRequest
        message =  CreateMessageRequest(content.text!!, direction)
        if (conversation_id != null) {
            sendMessage(conversation_id, message)
        }
    }

    override fun postBotMessage(session: UserSession, content: TextPayload) {
        this.sendMessage(session, content, OUTGOING)
    }


    override fun postVisitorMessage(session: UserSession, content: TextPayload) {
        this.sendMessage(session, content, INCOMING)
    }

    override fun handOff(session: UserSession, department: String) {
        val conversation_id = session.get(CONVERSATIONID) as Int?
        toggleStatus(conversation_id!!, "open")
        if(department == "Default"){
            assign(conversation_id!!, assignee = null, team = teamId)
        }else{
            assign(conversation_id!!, assignee = null, team = department.toInt())
        }
    }
    override fun close(session: UserSession) {
        val conversation_id : String = session[CONVERSATIONID]!! as String
        toggleStatus(conversation_id.toInt(), RESOLVED)
    }

    companion object : ExtensionBuilder<ISupport> {
        val logger = LoggerFactory.getLogger(ChatwootSupport::class.java)
        val CONVERSATIONID = "CONVERSATIONID"
        val CONTACTID = "CONTACTID"
        val OUTGOING = "outgoing"
        val INCOMING = "incoming"
        val OPEN = "open"
        val RESOLVED = "resolved"
        val ORGID = "account_id"
        val INBOX = "inbox_id"
        val ENDPOINT = "end_point"
        val TOKEN = "token"
        val BY = "by"
        val BOTID = "bot_id"
        val TEAMID = "team_id"
        override fun invoke(config: Configuration): ISupport {
            return ChatwootSupport(config)
        }
    }
}


fun main(args: Array<String>) {
    val support = Configuration(ISupport::class.qualifiedName!!, label = "default")
    support["assist"] = false
    support.put("end_point", "http://36.112.106.30:8883")
    support.put("token", "NtPVKRVHprxedVyoCLdjAJnD")
    support.put("account_id", "1")
    support.put("inbox_id", "27")
    val chatwoot = ChatwootSupport(support)

    val contact = UserInfo("Zhao", "", "")

    //println(Json.encodeToJsonElement(chatwoot.getConversations("1")).toPrettyString())


    //println(chatwoot.createContact(contact)?.toPrettyString())
    println("searching")
    // println(chatwoot.searchContact(contact)!!.toPrettyString())

    /*
    println("conversation")
    val sourceInfo = mapOf(
        "channelType" to "lark",
        "channelId" to "0",
        "id" to "adlfadlfadadfad"
    )

    // println(chatwoot.createConversation(7, 8, "lark:0:1231413410", sourceInfo).toPrettyString())

    println("message")
    val message = CreateMessageRequest("this is a test too", "incoming")
    //println(chatwoot.createMessage(9, message).toPrettyString())

    // chatwoot.toggleStatus(9, "open")
     */
}