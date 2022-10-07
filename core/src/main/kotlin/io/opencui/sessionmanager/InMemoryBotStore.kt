package io.opencui.sessionmanager

import io.opencui.core.BotInfo
import io.opencui.core.PerpetualCache

class InMemoryBotStore: IBotStore {
    private val cache = PerpetualCache<String, String>()
    override fun putRaw(botInfo: BotInfo, key: String, version: String): Boolean {
        cache["agent:${botInfo.org}:${botInfo.agent}:${botInfo.lang}:${botInfo.branch}|$key"] = version
        return true
    }

    override fun getRaw(botInfo: BotInfo, key: String): String? {
        return cache["agent:${botInfo.org}:${botInfo.agent}:${botInfo.lang}:${botInfo.branch}|$key"]
    }

    override fun toString(): String {
        return "InMemoryBotStore(cache=${cache})"
    }


}