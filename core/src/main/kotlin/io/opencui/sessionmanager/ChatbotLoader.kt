package io.opencui.sessionmanager

import io.opencui.core.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader

object ChatbotLoader {
    val logger: Logger = LoggerFactory.getLogger(ChatbotLoader::class.java)
    val chatbotCache = LRUCache<String, RecyclableAgentResource>(PerpetualCache())

    fun fetchValidCache(botInfo: BotInfo): RecyclableAgentResource? {
        val key = genKey(botInfo)
        val file = File("./jardir/${botInfo.org}_${botInfo.agent}_${botInfo.lang}_${botInfo.branch}.jar")
        if (chatbotCache[key]?.lastModified == file.lastModified()) return chatbotCache[key]
        return null
    }

    // load chatbot based on BotInfo and BotVersion
    fun findChatbotByQualifiedName(botInfo: BotInfo): IChatbot {
        return fetchValidCache(botInfo)?.chatbot ?: loadChatbot(botInfo).chatbot
    }

    fun findClassLoaderByQualifiedName(botInfo: BotInfo): ClassLoader {
        return fetchValidCache(botInfo)?.classLoader ?: loadChatbot(botInfo).classLoader
    }

    private fun loadChatbot(botInfo: BotInfo): RecyclableAgentResource {
        val key = genKey(botInfo)
        chatbotCache[key]?.recycle()
        val file = File("./jardir/${botInfo.org}_${botInfo.agent}_${botInfo.lang}_${botInfo.branch}.jar")
        logger.info("URLClassLoader path : ${file.absolutePath}")
        val classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), javaClass.classLoader)
        val qualifiedAgentName = "${botInfo.org}.${botInfo.agent}.Agent"
        val kClass = Class.forName(qualifiedAgentName, true, classLoader).kotlin
        val chatbot = (kClass.constructors.first { it.parameters.isEmpty() }.call() as IChatbot)
        chatbotCache[key] = RecyclableAgentResource(chatbot, classLoader, file.lastModified())
        return chatbotCache[key]!!
    }

    private fun genKey(botInfo: BotInfo): String {
        return "${botInfo.org}:${botInfo.agent}:${botInfo.lang}:${botInfo.branch}"
    }

    data class RecyclableAgentResource(val chatbot: IChatbot, val classLoader: ClassLoader, val lastModified: Long): Recyclable {
        override fun recycle() {
            chatbot.recycle()
            (classLoader as? Closeable)?.close()
        }
    }
}

