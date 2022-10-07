package io.opencui.core

import com.fasterxml.jackson.databind.ObjectMapper
import io.opencui.sessionmanager.SessionManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import io.opencui.core.user.IUserIdentifier
import io.opencui.serialization.Json
import io.opencui.serialization.JsonObject



/**
 * For receiving purpose, we do not need to implement this, as it is simply a rest controller
 * or a websocket service.
 *
 * Only the channel that can send message/reply out need to implement this, for example RCS.
 */

data class BadRequestException(override val message: String) : RuntimeException()

// Some channels are supported by live agent, and chatbot is just first line defense.
interface IManaged {
    // Chatbot can close this channel.
    fun closeSession(id:String, botInfo: BotInfo) {}

    fun handOffSession(id:String, botInfo: BotInfo, department: String) {}
}

interface ChannelInfo {

}

data class Retry<T>(
    val times: Int,
    val isGood: (T) -> Boolean,
    val initialDelay: Long = 100, // 0.1 second
    val maxDelay: Long = 1000,    // 1 second
    val factor: Double = 2.0) {

    operator fun invoke(block: () -> T) : T {
        var ltimes = 1
        var currentDelay = initialDelay
        while(ltimes < times) {
            try {
                val res = block()
                if (isGood(res)) {
                    return res
                } else {
                    println("retry the $ltimes")
                }
            } catch (e: Exception) {
                // you can log an error here and/or make a more finer-grained
                // analysis of the cause to see if retry is needed
            }

            Thread.sleep(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            ++ltimes
        }
        return block() // last attempt
    }
}

data class FramelyRequest (
        val text: String,
        val sessionId: String,
        val initial: Boolean = true,
        val events: List<FrameEvent> = emptyList())

data class FramelyResponse(
    val response: Map<String, List<String>>,
    val du: List<FrameEvent> = emptyList(),
    val state: JsonObject? = null)
//
// Dispatcher can be used to different restful controller to provide conversational
// interface for various channel.
//
// channelId is in form of channelType:channelLabel, and userId is a channel specific user
// identifier in string.
//
object Dispatcher {
    lateinit var sessionManager: SessionManager
    val logger: Logger = LoggerFactory.getLogger(Dispatcher::class.java)
    val mapper = ObjectMapper()

    // This is used to make sure that we have a singleton to start the task.
    val timer = Timer()

    // when this is true, we always delete existing index.
    var deleteExistingIndex: Boolean = true
    // this is deployment wide parameter.
    var memoryBased: Boolean = true

    fun getChatbot(botInfo: BotInfo) : IChatbot {
        return sessionManager.getAgent(botInfo)
    }

    fun getSupport(botInfo: BotInfo): ISupport? {
        return getChatbot(botInfo).getExtension<ISupport>()
    }

    fun closeSession(target: IUserIdentifier, botInfo: BotInfo) {
        if (target.channelType != null && target.channelLabel != null) {
            val channel = getChatbot(botInfo).getChannel(target.channelType!!, target.channelLabel!!)
            if (channel != null && channel is IManaged) {
                (channel as IManaged).closeSession(target.userId!!, botInfo)
            }
        }
        sessionManager.deleteUserSession(target, botInfo)

    }

    fun send(target: IUserIdentifier, botInfo: BotInfo, msgs: List<String>) {
        val channel = getChatbot(botInfo).getChannel(target.channelType!!, target.channelLabel!!)
        if (channel != null && channel is IMessageChannel) {
            for (msg in msgs) {
                channel.send(target.userId!!, msg, botInfo)
            }
        } else {
            logger.info("Cann't find ${target.channelType} for ${botInfo}")
        }
    }

    fun getUserSession(userInfo: IUserIdentifier, botInfo: BotInfo): UserSession? {
        return sessionManager.getUserSession(userInfo, botInfo).apply { this?.botInfo = botInfo }
    }

    fun createUserSession(userInfo: IUserIdentifier, botInfo: BotInfo): UserSession {
        val session = sessionManager.createUserSession(userInfo, botInfo)
        session.botInfo = botInfo
        val support = getSupport(botInfo)
        if (support != null && !support.isInitiated(session)) {
            logger.info("init session now...")
            // TODO(sean): we need to get this back for chatwoot.
            support.initSession(session)
        }

        // This make UserIdentifier available to any frame as global.
        // There are three different use case:
        // 1. single channel only.
        // 2. multichannel but with no omnichannel requirements.
        // 3. multichannel with omnichannel requirement.
        // For #1 and #2, the follow the good enough.
        session.setUserIdentifier(session.userIdentifier)
        return session
    }


    fun process(userInfo: IUserIdentifier, botInfo: BotInfo, message: TextPayload) {
        if (getUserSession(userInfo, botInfo) == null) {
            val userSession = createUserSession(userInfo, botInfo)
            // start the conversation from the Main.
            val events = listOf(FrameEvent("Main", emptyList(), emptyList(), "${botInfo.org}.${botInfo.agent}"))
            getReply(userSession, message, events)
        }else{
            val userSession = getUserSession(userInfo, botInfo)!!
            getReply(userSession, message)
        }
    }

    fun process(userInfo: IUserIdentifier, botInfo: BotInfo, events: List<FrameEvent>) {
        val userSession  = if (getUserSession(userInfo, botInfo) == null) {
            createUserSession(userInfo, botInfo)
        }else {
            getUserSession(userInfo, botInfo)!!
        }
        // start the conversation from the Main.
        getReply(userSession, null, events)
    }


    fun getReply(userSession: UserSession, message: TextPayload? = null, events: List<FrameEvent> = emptyList()) {
        val msgId = message?.msgId
        // if there is no msgId, or msgId is not repeated, we handle message.
        if (msgId != null && !userSession.pastMessages.isNew(msgId)) return

        val userInfo = userSession.userIdentifier
        val botInfo = userSession.botInfo
        // For now, we only handle text payload, but we can add other capabilities down the road.
        val textPaylaod = message

        logger.info("Got $textPaylaod from ${userInfo.channelType}:${userInfo.channelLabel}/${userInfo.userId} for ${botInfo}")

        val support = getSupport(botInfo)

        logger.info("$support with hand off is based on:${userSession.botOwn}")

        if (!userSession.botOwn && support == null) {
            logger.info("No one own this message!!!")
            throw BadRequestException("No one own this message!!!")
        }

        // always try to send to support
        if (textPaylaod != null) support?.postVisitorMessage(userSession, textPaylaod)
        if(!userSession.botOwn){
            logger.info("$support already handed off")
            return
        }
        val channel = getChatbot(botInfo).getChannel(userInfo.channelType!!, userInfo.channelLabel!!)!!
        logger.info(channel.info.toString())
        val query = textPaylaod?.text ?: ""
        if (userSession.botOwn) {
            // Let other side know that you are working on it
            if (channel is IMessageChannel) {
                logger.info("send hint...")
                if (message?.msgId != null) {
                    channel.markSeen(userInfo.userId!!, botInfo, message.msgId)
                    channel.typing(userInfo.userId!!, botInfo)
                }
            }

            // always add the RESTFUL just in case.
            val msgs = getReplyForChannel(userSession, query, userInfo.channelType!!, events)

            for (msg in msgs) {
                support?.postBotMessage(userSession, msg as TextPayload)
            }

            logger.info("send $msgs to ${userInfo.channelType}/${userInfo.userId} from ${botInfo}")

            send(userInfo, botInfo, msgs)

            // Wait until we are done with process, this is not foolproof, just slightly better.
            if (msgId != null) userSession.pastMessages.update(msgId)
        } else {
            if (support == null || !support.info.assist) return
            // assist mode.
            val msgs = getReplyForChannel(userSession, query, userInfo.channelType!!, events)
            for (msg in msgs) {
                support.postBotMessage(userSession, msg as TextPayload)
            }
        }
    }

    private fun getReplyForChannel(
        session: UserSession,
        query: String,
        targetChannel: String,
        events: List<FrameEvent> = emptyList()): List<String> {
        val msgMap = sessionManager.getReply(session, query, listOf(targetChannel, SideEffect.RESTFUL), events)
        val msg = if (!msgMap[targetChannel].isNullOrEmpty()) msgMap[targetChannel] else msgMap[SideEffect.RESTFUL]
        logger.info("get $msg for channel $targetChannel")
        return msg!!
    }

    // This is called to trigger handoff.
    fun handOffSession(target: IUserIdentifier, botInfo: BotInfo, department:String) {
        logger.info("handoff ${target.userId} at ${target.channelType} on ${botInfo} with depatment ${department}")


        val channel = getChatbot(botInfo).getChannel(target.channelType!!, target.channelLabel!!)
        if (channel == null) {
            logger.info("Channel ${target.channelType} not found.")
        }

        // remember to change botOwn to false.
        val userSession = sessionManager.getUserSession(target, botInfo)!!
        userSession.botOwn = false

        if (channel != null && channel is IManaged) {
            (channel as IManaged).handOffSession(target.userId!!, botInfo, department)
        } else {
            getSupport(botInfo)?.handOff(userSession, department)
        }
    }

    fun callIntent(intentStr: String, userSession: UserSession){
        if (userSession.botOwn) {
            val input = intentStr
            val dm = DialogManager()
            val rawQuery = parseExplanation(input)!!
            val convertedFrameEvents = dm.convertSpecialFrameEvent(userSession, rawQuery.frames)
            for (event in convertedFrameEvents) {
                event.source = EventSource.USER
            }
            getReply(userSession, textMessage("", UUID.randomUUID().toString()), convertedFrameEvents)
        }
    }

    fun parseExplanation(command: String): ParsedQuery? {
        return Json.decodeFromString<ParsedQuery>(command)
    }
}