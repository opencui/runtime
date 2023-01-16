package io.opencui.sessionmanager

import io.opencui.core.BotInfo
import redis.clients.jedis.JedisPool

class RedisBotStore(private val pool: JedisPool): IBotStore {
    override fun putRaw(botInfo: BotInfo, key: String, value: String): Boolean {
        pool.resource.use {
            it.set("agent:${botInfo.org}:${botInfo.agent}:${botInfo.lang}:${botInfo.branch}|$key", value)
        }
        return true
    }

    override fun getRaw(botInfo: BotInfo, key: String): String? {
        return pool.resource.use {
            it.get("agent:${botInfo.org}:${botInfo.agent}:${botInfo.lang}:${botInfo.branch}|$key")
        }
    }
}
