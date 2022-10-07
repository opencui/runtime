package io.opencui.sessionmanager

import io.opencui.core.BotInfo
import io.opencui.core.ExpirableCache
import io.opencui.core.PerpetualCache
import io.opencui.core.UserSession
import java.util.concurrent.TimeUnit

class InMemorySessionStore: ISessionStore {
    val cache = ExpirableCache<String, UserSession>(PerpetualCache(), TimeUnit.MINUTES.toNanos(30))
    override fun getSession(channel: String, id:String, botInfo: BotInfo): UserSession? {
        return cache[ISessionStore.key(channel, id, botInfo)]
    }

    override fun deleteSession(channel: String, id:String, botInfo: BotInfo): Boolean {
        return cache.remove(ISessionStore.key(channel, id, botInfo)) != null
    }

    override fun updateSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean {
        val key = ISessionStore.key(channel, id, botInfo)
        return if (cache[key] != null) {
            cache[key] = session
            true
        } else {
            false
        }
    }

    override fun saveSession(channel: String, id: String, botInfo: BotInfo, session: UserSession): Boolean {
        cache[ISessionStore.key(channel, id, botInfo)] = session
        return true
    }
}