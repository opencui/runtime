package io.opencui.dispatcher

import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.*
import io.opencui.core.Dispatcher
import io.opencui.core.FramelyRequest
import io.opencui.core.FramelyResponse
import io.opencui.core.user.UserInfo
import io.opencui.sessionmanager.TurnStateLogger
import io.opencui.sessionmanager.ChatbotLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.io.PrintWriter
import java.io.StringWriter

/**
 * This is useful for serving universal messages
 */
@RestController
class FramelyController() {
	val logger: Logger = LoggerFactory.getLogger(FramelyController::class.java)

	@GetMapping("/health_check")
	fun healthCheck() {
		logger.info("come into health check")
	}

	@PostMapping("/v1/tryitnow/{lang}")
	fun postResponse(
		@PathVariable lang: String,
		@RequestBody body: FramelyRequest
	): FramelyResponse {
		logger.info("body: " + body.toString())
		// clear thread local logs
		TurnStateLogger.clear()

		val sessionManager = Dispatcher.sessionManager

		val sessionId = body.sessionId
		val channel = SideEffect.RESTFUL

		val userInfo = UserInfo(channel, sessionId, null)

		logger.info("body : ${body}; session id : ${sessionId}; lang : ${lang};")
		lateinit var userSession: UserSession
		lateinit var responses: Map<String, List<String>>
		var flag = false
		val botInfo = BotInfo("", "", lang)
		val firstUserSession = sessionManager.getUserSession(userInfo, botInfo)
		
		if (body.initial || firstUserSession == null) {
			userSession = sessionManager.createUserSession(userInfo, botInfo)
			val support = Dispatcher.getSupport(botInfo)
			if (support != null && !support.isInitiated(userSession)) {
				Dispatcher.logger.info("init session now...")
				// TODO(sean): we need to get this back for chatwoot.
				support.initSession(userSession)
			}
			userSession.setUserIdentifier(userSession.userIdentifier)
			logger.info("userIndentifier: ${userSession.userIdentifier}")
		} else {
			flag = true
			userSession = sessionManager.getUserSession(userInfo, botInfo)!!
		}


		try {
			responses = if(flag) {
				sessionManager.getReply(userSession, body.text, listOf(userInfo.channelType!!), body.events)
			} else {
				val mainEvent = FrameEvent("Main", emptyList(), emptyList(), "${ChatbotLoader.botPrefix}")
				logger.info("Always start from $mainEvent")
				val events = listOf(mainEvent) + body.events
				sessionManager.getReply(userSession, body.text, listOf(userInfo.channelType!!), events)
			}
		} catch (e: Exception) {
			val sw = StringWriter()
			val pw = PrintWriter(sw)
			e.printStackTrace(pw)
			pw.flush()
			logger.error("fail to response for query : ${body.text}; error : $sw")
			throw ResponseStatusException(
				HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get reply: ${sw}", e
			)
		}

		logger.info("responses: $responses")
		val state = TurnStateLogger.getState().apply {
			replace(
				"du",
				io.opencui.serialization.Json.encodeToJsonElement(userSession.events.filter { it.turnId == userSession.turnId })
			)
			replace("session", TextNode(userSession.toSessionString()))
		}

		return FramelyResponse(responses, userSession.events.filter { it.turnId == userSession.turnId }, state)

	}

	companion object {
        const val channel = "restful"
        const val branch = "master"
    }
}
