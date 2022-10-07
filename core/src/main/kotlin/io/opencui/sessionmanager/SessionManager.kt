package io.opencui.sessionmanager

import io.opencui.core.*
import io.opencui.core.da.ComponentDialogAct
import io.opencui.core.user.IUserIdentifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * We assume that at no circumstances that user will access more than one language at the same time.
 */
interface ISessionStore {
    fun getSession(channel: String, id:String, botInfo: BotInfo): UserSession?
    fun deleteSession(channel: String, id:String, botInfo: BotInfo): Boolean
    fun updateSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean
    fun saveSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean

    companion object {
        fun key(channel: String, id:String, botInfo: BotInfo): String {
            // We do not use language as part of key so that multiple language support is easier.
            return "session:${channel}:${id}:${botInfo.org}:${botInfo.agent}::${botInfo.branch}"
        }
    }
}


// Some time, we need to save the bot related information across different versions for all users.
interface IBotStore {
    fun putVersion(botInfo: BotInfo, version: String): Boolean { return putRaw(botInfo, "version", version)}
    fun getVersion(botInfo: BotInfo): String? { return getRaw(botInfo, "version") }


    fun putRaw(botInfo: BotInfo, key: String, value: String): Boolean
    fun getRaw(botInfo: BotInfo, key: String): String?
}

/**
 * The conversion state need to be saved in UserSession so that the same customer can be served
 * by different instances if we need to. Consider this as short term memory for the conversation,
 * we can have long term memory about what user have consumed (but may not include how user got
 * these services).
 *
 * So we need two different interfaces: at dispatcher level, we manage the support channel as
 * well, at session manager level, we simply respond to user query. Of course, we can reply
 * with messages that goes to support as well.
 *
 * SessionManager provide interface that wraps a particular version of agent, so that it is easy
 * for both generic and channel specific rest controller to connect user with bot.
 * This is useful so that different restful api (for different channel) can reuse the same
 * session manager.
 *
 * To support try it now, we need to return response from all channels. We add targetChannels
 * if it is null, we use the source channels, if it is empty list, we return all channels,
 * otherwise, we return the response for every channel specified.
 *
 * For now, bot takes in raw text as query, and returns the raw string that contains the structured
 * messages.
 *
 */
class SessionManager(private val sessionStore: ISessionStore, val botStore: IBotStore? = null) {

    private val dm: DialogManager = DialogManager()

    fun getUserSession(channel: IUserIdentifier, botInfo: BotInfo): UserSession? {
        // We try to deserialize the saved session, but if we can not, return null as if we
        // did not see this user before.
        return try {
            sessionStore.getSession(channel.channelId(), channel.userId!!, botInfo)
        } catch (e: Exception) {
            sessionStore.deleteSession(channel.channelId(), channel.userId!!, botInfo)
            null
        }
    }

    fun createUserSession(channel: IUserIdentifier, botInfo: BotInfo): UserSession {
        sessionStore.deleteSession(channel.channelId(), channel.userId!!, botInfo)
        val createdSession = ChatbotLoader
            .findChatbotByQualifiedName(botInfo)
            .createUserSession(channel.channelType!!, channel.userId!!, channelLabel = channel.channelLabel)
            .apply {
                sessionStore.saveSession(channel.channelId(), channel.userId!!, botInfo, this)
            }
        logger.info("create session with bot version: ${createdSession.chatbot?.agentBranch}")
        return createdSession
    }

    /**
     * This deletes the corresponding user session so that next conversation starts from scratch.
     * Of course the user history (services enjoyed) should be saved somehow.
     */
    fun deleteUserSession(channel: IUserIdentifier, botInfo: BotInfo) {
        sessionStore.deleteSession(channel.channelId(), channel.userId!!, botInfo)
    }

    fun updateUserSession(channel: IUserIdentifier, botInfo: BotInfo, session: UserSession) {
        sessionStore.updateSession(channel.channelId(), channel.userId!!, botInfo, session)
    }


    /**
     * This method will be called when contact submit an query along with events (should we allow this?)
     * And we need to come up with reply to move the conversation forward.
     *
     * event: the extra signal encoded.
     *
     * session: if the bot can handle it, we should keep botOwn as true, if bot can not, it should
     *          set the botOwn as false, and inform support.
     *
     * channel: the format that is expected.
     */
    fun getReply(session: UserSession,
                 query: String,
                 targetChannels: List<String>,
                 events: List<FrameEvent> = emptyList()): Map<String, List<String>> {
        assert(targetChannels.isNotEmpty())
        session.targetChannel = targetChannels
        val responses = dm.response(query, events, session)
        updateUserSession(session.userIdentifier, session.botInfo, session)
        return convertBotUtteranceToText(session, responses, session.targetChannel)
    }

    /**
     * This the place where we can remove extra prompt if we need to.
     */
    private fun convertBotUtteranceToText(session: UserSession, results: List<ActionResult>, targetChannels: List<String>): Map<String, List<String>> {
        val responses : List<ComponentDialogAct> = results.filter { it.botUtterance != null && it.botOwn }.map { it.botUtterance!!}.flatten()
        val rewrittenResponses = session.rewriteDialogAct(responses)
        return targetChannels.associateWith { k -> rewrittenResponses.map {"""${if (k == SideEffect.RESTFUL) "[${it::class.simpleName}]" else ""}${it.templates.pick(k).invoke()}"""} }
    }

    /**
     * Return the best implementation for given agentId.
     */
    fun getAgent(botInfo: BotInfo): IChatbot {
        return ChatbotLoader.findChatbotByQualifiedName(botInfo)
    }

    companion object {
        private val master = "master"
        private val logger: Logger = LoggerFactory.getLogger(SessionManager::class.java)
    }
}

