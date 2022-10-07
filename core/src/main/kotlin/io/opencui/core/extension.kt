package io.opencui.core

import io.opencui.core.user.IUserIdentifier
import io.opencui.core.user.UserInfo
import io.opencui.serialization.Json
import io.opencui.serialization.JsonObject
import org.slf4j.LoggerFactory
import java.io.Serializable

// This is used for creating the instance for T, useful for channels and supports.
/***
 * Section for configuration.
 */
interface IExtension

interface ExtensionBuilder<T:IExtension> : (Configuration) -> T

/**
 * This class holds a builder for each channel, and create a channel instance for given chatbot
 * on the first time it was requested.
 */
data class ExtensionManager<T:IExtension>(val contract: String) {
    val holder = mutableMapOf<String, T>()
    val types = mutableMapOf<String, String>()
    val builders: MutableMap<String, ExtensionBuilder<T>> = mutableMapOf()

    fun get(label: String = "default") : T? {
        if (!holder.containsKey(label)) {
            val builder = builders[label]
            if (builder != null) {
                val triple = Pair(contract, label)
                val config = Configuration.get(triple)
                println(config)
                println(config as Map<String, Any>)
                if (config != null) {
                    holder[label] = builder.invoke(config)
                }
            }
        }
        return holder[label]
    }

    fun builder(label: String, builder: ExtensionBuilder<T>, init: ConfiguredBuilder<T>.()->Unit) {
        val configuredBuilder = ConfiguredBuilder(contract, label, builder)
        configuredBuilder.init()
        builders[label] = configuredBuilder.builder
    }

    companion object {
        val logger = LoggerFactory.getLogger(ExtensionManager::class.java)
    }
}

// The configurable should be able to used in old way, and property way.
open class Configuration(val contract: String, val label: String): Serializable, HashMap<String, Any>() {
    init {
        val oldCfg = configurables.get(toPair())
        if (oldCfg == null) {
            configurables[toPair()] = this
        } else {
            this.map{ oldCfg[it.key] = it.value }
        }
    }

    /**
     * if the assist is true, chatbot will continue to send the automatical response to support so that
     * live agent can decide what to do with this. For support.
     */
    val assist: Boolean
        get() = this["assist"] == true

    // For templated provider.
    val conn: String
        get() = this["conn"]!! as String
    
    val url: String
        get() = this["url"]!! as String

    fun toPair() = Pair(contract, label)

    override fun toString(): String {
        return """$contract:$label:${super.toString()}"""
    }

    fun id() : String = "$contract.$label"


    
    
    companion object {
        const val DEFAULT = "default"
        val configurables = mutableMapOf<Pair<String, String>, Configuration>()

        fun get(triple: Pair<String, String>): Configuration? {
            return configurables[triple]
        }

        fun get(contract: String, label: String = DEFAULT): Configuration? {
            return configurables[Pair(contract, label)]
        }

        fun startsWith(key: String, prefixes: Set<String>) : String? {
            return prefixes.firstOrNull{key.startsWith(it)}
        }

        fun loadFromProperty() {
            val props = System.getProperties()
            val cfgPrefixes = mutableMapOf<String, Configuration>()
            for (info in configurables) {
                val cfg = info.value
                cfgPrefixes[cfg.id()] = cfg
            }

            for (key in props.keys()) {
                if (key is String) {
                    val match = startsWith(key, cfgPrefixes.keys)
                    if (match != null) {
                        val remainder = key.substring(match.length)
                        val cfg =  cfgPrefixes[match]
                        if (cfg != null) {
                            cfg[remainder] = props.getProperty(key)
                        }
                    }
                }
            }
        }
    }
}

data class ConfiguredBuilder<T:IExtension>(val contract: String, val label: String, val builder: ExtensionBuilder<T>) {
    val config = Configuration(contract, label)

    fun put(key: String, value: Any) {
        config[key] = value
    }
}


/***
 * Section for support.
 */



/**
 * Three things needed to work together to make support work:
 * a. messenger system where support agent can get context, and send replies.
 * b. ISupport implementation on the dispatcher side that can move information to right place.
 * c. dispatcher endpoint that can forward the message back to customers via correct channel.
 *
 * Conversation has two different mode, bot control and agent control.
 */
interface ISupport : IExtension {
    // The label for support.
    fun name(): String

    val info: Configuration

    fun isInitiated(session: UserSession) : Boolean

    // This function return the room id in String so that we can forward conversation to right
    // place. By exposing the createRoom, we can potentially have different rooms for same contact
    // at different time, or not, depending on how createRoom is used.
    fun initSession(session: UserSession)

    // This make sure that we keep all the information needed。
    fun postBotMessage(contact: UserSession, content: TextPayload)
    fun postVisitorMessage(contact: UserSession, content: TextPayload)

    // This is used to hand control to live agent.
    fun handOff(contact: UserSession, department:String)
    fun close(session: UserSession)
}

/***
 * Section for channels
 */
/**
 * For receiving purpose, we do not need to implement this, as it is simply a rest controller
 * or a websocket service.
 *
 * Only the channel that can send message/reply out need to implement this, for example RCS.
 */
interface IChannel : IExtension {
    val info: Configuration?

    // This should be call when session is new, so that we can fill the session with some
    // initial value about user.
    // This is not used right now.
    fun getProfile(botInfo: BotInfo, id: String): IUserIdentifier?

    data class Status(val message: String)
}

interface IAsyncChannel : IChannel {
    // Channel implementation need to decode the message into the actual format that was
    // used by that channel. We assume that message is json encoding in string for now.
    // contact is also a channel dependent notation for user.
    fun send(id: String, payloadStr: String, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status {
        val payloadTrimed =  payloadStr.trim()
        if (payloadTrimed[0] != '{') {
            // we got pure text
            sendWhitePayload(id, textMessage(payloadTrimed), botInfo, source)
        } else {
            // Now we assume that we are getting formatted payload
            val payloadJson = Json.parseToJsonElement(payloadTrimed)
            val type = payloadJson["type"].asText()
            if ( type in setOf("text", "rich", "listText", "listRich")) {
                val payload : IWhitePayload = Json.decodeFromJsonElement(payloadJson)
                sendWhitePayload(id, payload, botInfo, source)
            } else {
                sendRawPayload(id, payloadJson as JsonObject, botInfo, source)
            }
        }
        return IChannel.Status("works")
    }

    fun sendWhitePayload(id: String, rawMessage: IWhitePayload, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status
    fun sendRawPayload(uid: String, rawMessage: JsonObject, botInfo: BotInfo, source: IUserIdentifier? = null): IChannel.Status

    // this is used to let client know that bot/agent decide to close the session.
    fun close(id: String, botInfo: BotInfo) {}
}


interface IMessageChannel : IAsyncChannel {
    override fun sendWhitePayload(id: String, rawMessage: IWhitePayload, botInfo: BotInfo, source: IUserIdentifier?): IChannel.Status {
        preSend()
        val result =  when (rawMessage) {
            is TextPayload -> sendSimpleText(id, rawMessage, botInfo, source)
            is RichPayload -> sendRichCard(id, rawMessage, botInfo, source)
            is ListTextPayload -> sendListText(id, rawMessage, botInfo, source)
            is ListRichPayload -> sendListRichCards(id, rawMessage, botInfo, source)
        }
        postSend()
        return result
    }

    fun sendSimpleText(uid: String, rawMessage: TextPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendRichCard(uid: String, rawMessage: RichPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendListText(uid: String, rawMessage: ListTextPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status
    fun sendListRichCards(uid: String, rawMessage: ListRichPayload, botInfo: BotInfo, source: IUserIdentifier? = null) : IChannel.Status

    // This is the opportunity for channel to handle channel dependent messages.


    // This is used to let other side know that we are working with their request.
    fun typing(uid: String, botInfo: BotInfo) {}

    fun markSeen(uid: String, botInfo: BotInfo, messageId: String?=null) {}

    fun preSend() {}

    fun postSend() {}
}


/***
 * Section for providers
 */

//
// There are couple different concerns we need to deal with:
// 1. initialization from configurable (potentially from system property).
// 2. lazy init and instance accessible from end point. (implementation need to implement builder in companion)
// 3. We need some manager to manage the implementation instance, in case one service need more than one implementation.
//
/**
 * One should be able to access connection, and even session. The IService contains a set of functions.
 * Service is also attached to session, just like frame.
 */
interface IService: Serializable, IExtension

// After received the call from the third party, we create the corresponding event, and send to listener.
interface EventListener: Serializable {
    fun accept(botInfo: BotInfo, userInfo: UserInfo, event: FrameEvent)
}

class DispatcherEventListener : EventListener {
    override fun accept(botInfo: BotInfo, userInfo: UserInfo, event: FrameEvent) {
        Dispatcher.process(userInfo, botInfo, listOf(event))
    }
}

// This is for hosting endpoints, so that we can compose things.
interface IListener {
    fun addListener(listener: EventListener)
}



// All IProvider are the base for implementation. We need two separate type hierarchy
// The object should
interface IProvider : IService, IExtension {
    // This is to access the user somehow.
    var session: UserSession?
}

/**
 * For each service, we need to code gen a property of its service manager.
 */
interface ServiceManager<T>: Serializable {
    fun UserSession.get(): T
}

// We can potentially have a channel dependent service manager.
data class SimpleManager<T: IExtension>(val builder: () -> T) : ServiceManager<T> {
    // This will create
    var provider: T? = null

    override fun UserSession.get(): T {
        if (provider == null) {
            provider = builder.invoke()
        }
        return provider!!
    }
}


// We have two kind of provider: native provider, and templated provider.
// For each service, we code gen a manager, and then a service property.
// The service property should use get to get the actual provider.
