package io.opencui.sessionmanager

import io.opencui.core.BotInfo
import io.opencui.core.Dispatcher
import io.opencui.core.UserSession
import redis.clients.jedis.JedisPool
import java.io.*
import java.util.*

class RedisSessionStore(private val pool: JedisPool): ISessionStore {
    override fun getSession(channel: String, id: String, botInfo: BotInfo): UserSession? {
        var session: UserSession? = null
        val key = ISessionStore.key(channel, id, botInfo)
        val encodedSession = pool.resource.use {
            it.get(key)
        } ?: return null
        val decodedSession = Base64.getDecoder().decode(encodedSession)
        try {
            val objectIn = object : ObjectInputStream(ByteArrayInputStream(decodedSession)) {
                override fun resolveClass(desc: ObjectStreamClass?): Class<*> {
                    try {
                        val customClassLoader = ChatbotLoader.findClassLoader(botInfo)
                        return Class.forName(desc!!.name, true, customClassLoader)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return super.resolveClass(desc)
                }
            }
            objectIn.use {
                session = it.readObject() as? UserSession
            }
        } catch (ice: InvalidClassException) {
            Dispatcher.logger.info("version mismatch.")
            pool.resource.use {it.del(key)}
            Dispatcher.logger.info(ice.toString())
        }
        return session
    }

    override fun deleteSession(channel: String, id: String, botInfo: BotInfo): Boolean {
        val key = ISessionStore.key(channel, id, botInfo)
        pool.resource.use {
            it.del(key) > 0
        }
        return true
    }

    override fun updateSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean {
        val key = ISessionStore.key(channel, id, botInfo)
        val exist = pool.resource.use {
            it.exists(key)
        }
        return if (exist) {
            saveSession(channel, id, botInfo, session)
        } else {
            false
        }
    }

    override fun saveSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean {
        val key = ISessionStore.key(channel, id, botInfo)
        val byteArrayOut = ByteArrayOutputStream()
        val objectOut = ObjectOutputStream(byteArrayOut)
        objectOut.use {
            it.writeObject(session)
            val encodedSession = String(Base64.getEncoder().encode(byteArrayOut.toByteArray()))
            pool.resource.use { jed ->
                jed.set(key, encodedSession)
            }
        }
        return true
    }

    companion object {

    }
}
