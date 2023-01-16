package io.framely.dispatcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.opencui.core.*
import io.opencui.core.Dispatcher
import io.opencui.du.DucklingRecognizer
import io.opencui.du.TfRestBertNLUModel
import io.opencui.sessionmanager.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.io.File
import kotlin.system.exitProcess
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

@Configuration
@SpringBootApplication(scanBasePackages = ["io.opencui", "io.framely"])
class DispatchService(
	@Value("\${du.duckling}") val duDuckling: String,
	@Value("\${du.host}") val duHost: String,
	@Value("\${du.port}") val duPort: String,
	@Value("\${du.protocol}") val duProtocol: String,
	@Value("\${bot.prefix:}") val botPrefix: String,
	@Value("\${session.host:}") val storeHost: String,
	@Value("\${session.port:}") val storePort: String,
	@Value("\${indexing:}") val indexing: String
) {

	@EventListener(ApplicationReadyEvent::class)
	fun init() {
		ObjectMapper().registerModule(KotlinModule())

		RuntimeConfig.put(DucklingRecognizer::class, duDuckling)
		RuntimeConfig.put(TfRestBertNLUModel::class, Triple(duHost, duPort.toInt(), duProtocol))
		Dispatcher.memoryBased = false
		if (indexing.toBoolean()) {
			try {
				ChatbotLoader.init(File("./jardir/"), botPrefix)
			} catch (e: Exception) {
				e.printStackTrace()
			} finally {
				// applicationContext.close()
				exitProcess(0)
			}
		} else {
			// This make sure that we keep the existing index if we have it.
			val sessionManager = if (storeHost.isEmpty() || storePort.isEmpty()) {
				SessionManager(InMemorySessionStore(), InMemoryBotStore())
			} else {
				SessionManager(
					RedisSessionStore(JedisPool(JedisPoolConfig(), storeHost, storePort.toInt())),
					InMemoryBotStore())
			}

			Dispatcher.sessionManager = sessionManager

			Dispatcher.deleteExistingIndex = false
			ChatbotLoader.init(File("./jardir/"), botPrefix)
			Dispatcher.logger.info("finish the builder initialization.")
		}
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			println("******************************** start from native...")
			SpringApplication(DispatchService::class.java).run(*args)
		}
	}
}

