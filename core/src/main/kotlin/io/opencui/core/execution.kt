package io.opencui.core

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.du.*
import io.opencui.serialization.Json
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.full.memberProperties


inline fun<T> timing(msg: String, function: () -> T): T {
    val startTime = System.currentTimeMillis()
    val result: T = function.invoke()
    val endTime = System.currentTimeMillis()
    println("$msg consumed ${endTime - startTime} millisecond.")
    return result
}

/**
 * DialogManager is used to drive a statechart configured by builder using input event created by end user.
 *
 * The computation is turn based. At each turn, it will follow the state transition rules defined by statechart,
 * until it run into turn termination signal, where it will hand turn to user and wait next input.
 *
 *
 */
class DialogManager {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(DialogManager::class.java)
        val validEndState = setOf(Scheduler.State.INIT, Scheduler.State.POST_ASK, Scheduler.State.RECOVER)
        val CONFIRMATIONSTSTUS = io.opencui.core.confirmation.IStatus::class.qualifiedName!!
        val CONFIRMATIONPACKAGE = CONFIRMATIONSTSTUS.split(".").subList(0,5).joinToString(".")
        val HASMORESTATUS = io.opencui.core.hasMore.IStatus::class.qualifiedName!!

    }

    /**
     * high level response, called before dialog understanding (DU, DU is handled by this method first, before it
     * calls the low level response method).
     */
    fun response(query: String, frameEvents: List<FrameEvent>, session: UserSession): List<ActionResult> {
        val expectations = findDialogExpectation(session)
        val duReturnedFrameEvent = session.chatbot!!.stateTracker.convert(session.userIdentifier.toString(), query, DialogExpectations(expectations))
        logger.info("Du returned frame events : $duReturnedFrameEvent")
        logger.info("Extra frame events : $frameEvents")
        val convertedFrameEventList = convertSpecialFrameEvent(session, frameEvents + duReturnedFrameEvent)
        for (event in convertedFrameEventList) {
            // events from user
            event.source = EventSource.USER
        }
        logger.info("Converted frame events : $convertedFrameEventList")
        return response(ParsedQuery(query, convertedFrameEventList), session)
    }

    /**
     * Low level response, after DU is done.
     */
    fun response(pinput: ParsedQuery, session: UserSession): List<ActionResult> {
        session.turnId += 1

        // When we did not understand what user said.
        // TODO: figure out different ways that we do not get it, so that we reply smarter.
        val frameEvents = pinput.frames
        if (frameEvents.isEmpty()) {
            val delegateActionResult = session.findSystemAnnotation(SystemAnnotationType.IDonotGetIt)?.searchResponse()?.run(session)
            if (delegateActionResult != null) {
                return listOf(delegateActionResult)
            }
        }

        session.addEvents(frameEvents)

        val actionResults = mutableListOf<ActionResult>()
        var currentTurnWorks: List<Action>

        logger.info("session state before turn ${session.turnId} : ${session.toSessionString()}")

        var botOwn = session.botOwn
        // In each round, we either consume an event, or switch the state, until no rule can be fired
        // we need to make sure there is no infinite loop (each round changes the state)
        var maxRound = 100 // prevent one session from taking too much resources
        do {
            var schedulerChanged = false
            while (session.schedulers.size > 1) {
                val top = session.schedulers.last()
                if (top.isEmpty()) {
                    session.schedulers.removeLast()
                    schedulerChanged = true
                } else {
                    break
                }
            }

            currentTurnWorks = session.userStep()
            if (schedulerChanged && currentTurnWorks.isEmpty()) {
                session.schedule.state = Scheduler.State.RESCHEDULE
                currentTurnWorks = session.userStep()
            }

            check(currentTurnWorks.isEmpty() || currentTurnWorks.size == 1)
            if (currentTurnWorks.isNotEmpty()) {
                try {
                    currentTurnWorks[0].run(session).let {
                        actionResults += it.apply { this.botOwn = botOwn }
                    }
                } catch (e: Exception) {
                    session.schedule.state = Scheduler.State.RECOVER
                    throw e
                }
            }

            botOwn = session.botOwn
            if (--maxRound <= 0) break
        } while (currentTurnWorks.isNotEmpty())

        if (!validEndState.contains(session.schedule.state)) {
            val currentState = session.schedule.state
            session.schedule.state = Scheduler.State.RECOVER
            throw Exception("END STATE of scheduler is invalid STATE : ${currentState}")
        }

        logger.info("session state after turn ${session.turnId} : ${session.toSessionString()}")

        if (actionResults.isEmpty() && session.schedule.isNotEmpty()) {
            if (session.lastTurnRes.isNotEmpty()) return session.lastTurnRes
            val delegateActionResult = session.findSystemAnnotation(SystemAnnotationType.IDonotGetIt)?.searchResponse()?.run(session)
            if (delegateActionResult != null) {
                if (delegateActionResult.actionLog != null) {
                    actionResults += delegateActionResult
                }
            }
        }

        session.lastTurnRes = actionResults
        return actionResults
    }

    fun findDialogExpectation(session: UserSession): DialogExpectation? {
        val entity = session.schedule.lastOrNull()
        if (session.schedule.isEmpty() || entity == null || entity.askStrategy() is ExternalEventStrategy) return null
        val topFrameWrapperFiller = session.schedule.filterIsInstance<AnnotatedWrapperFiller>().lastOrNull { it.targetFiller is FrameFiller<*> }!!
        val topFrame = (topFrameWrapperFiller.targetFiller as FrameFiller<*>).frame()
        val res = mutableListOf<ExpectedFrame>()
        when (topFrame) {
            is PagedSelectable<*> -> {
                //TODO(xiaobo) what is the actual order in the stack?
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    val potentialHasMoreFiller = targetFiller.parent?.parent?.parent?.parent
                    if ((potentialHasMoreFiller as? FrameFiller<*>)?.frame() is HasMore) {
                        // hardcode status for HasMore
                        res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", io.opencui.core.hasMore.IStatus::class.qualifiedName!!)
                        val potentialMVFiller = potentialHasMoreFiller.parent?.parent
                        if (potentialMVFiller != null) {
                            findExpectationByFiller(session, potentialMVFiller)?.let {
                                res += it
                            }
                        }
                    } else {
                        val recTargetExp = findExpectationByFiller(session, targetFiller)
                        if (recTargetExp != null) res += recTargetExp
                    }
                }
                res += ExpectedFrame(PagedSelectable::class.qualifiedName!!, "index", io.opencui.core.Ordinal::class.qualifiedName!!)
            }
            is Confirmation -> {
                res += ExpectedFrame(Confirmation::class.qualifiedName!!, "status", io.opencui.core.confirmation.IStatus::class.qualifiedName!!)
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    val potentialPagedSelectableFiller = targetFiller.parent?.parent
                    if (potentialPagedSelectableFiller is FrameFiller<*> && potentialPagedSelectableFiller.frame() is PagedSelectable<*>) {
                        val pageFrame = potentialPagedSelectableFiller.frame() as PagedSelectable<*>
                        val recTarget = pageFrame.target
                        val recSlot = pageFrame.slot
                        if (recTarget != null) {
                            val recTargetFiller = session.findWrapperFillerWithFrame(recTarget)
                            val expectedTargetFiller = if (recSlot.isNullOrEmpty()) {
                                recTargetFiller?.targetFiller
                            } else {
                                (recTargetFiller?.targetFiller as? FrameFiller<*>)?.fillers?.get(recSlot)?.targetFiller
                            }
                            if (expectedTargetFiller != null) {
                                findExpectationByFiller(session, expectedTargetFiller)?.let {
                                    res += it
                                }
                            }
                            if (expectedTargetFiller is MultiValueFiller<*>) {
                                res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", io.opencui.core.hasMore.IStatus::class.qualifiedName!!)
                            }
                        }
                    }
                    findExpectationByFiller(session, targetFiller)?.let {
                        res += it
                    }
                }
            }
            is HasMore -> {
                res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", io.opencui.core.hasMore.IStatus::class.qualifiedName!!)
                val multiValueFiller = topFrameWrapperFiller.parent as? MultiValueFiller<*>
                if (multiValueFiller != null) {
                    findExpectationByFiller(session, multiValueFiller)?.let {
                        res += it
                    }
                }
            }
            is BoolGate -> {
                res += ExpectedFrame(BoolGate::class.qualifiedName!!, "status", io.opencui.core.booleanGate.IStatus::class.qualifiedName!!)
                val targetFiller = (topFrameWrapperFiller.parent as? AnnotatedWrapperFiller)?.targetFiller
                if (targetFiller != null) {
                    if (targetFiller is FrameFiller<*>) {
                        val parent = targetFiller.parent?.parent as? FrameFiller<*>
                        if (parent != null) {
                            res += ExpectedFrame(parent.qualifiedEventType()!!, targetFiller.attribute, targetFiller.qualifiedTypeStr())
                        }
                    }
                    findExpectationByFiller(session, targetFiller)?.let {
                        res += it
                    }
                }
            }
            else -> {
                val frameFiller = topFrameWrapperFiller.targetFiller
                val index = session.schedule.indexOf(frameFiller)
                val focusFiller = if (index != -1 && session.schedule.size > index+1) session.schedule[index+1] as? AnnotatedWrapperFiller else null
                val focus = focusFiller?.attribute
                if (frameFiller.qualifiedTypeStr() != io.opencui.core.Confirmation::class.qualifiedName!!
                    && (focusFiller?.targetFiller as? InterfaceFiller<*>)?.qualifiedTypeStr() == CONFIRMATIONSTSTUS) {
                    res += ExpectedFrame(Confirmation::class.qualifiedName!!, "status", CONFIRMATIONSTSTUS)
                }
                res += ExpectedFrame(frameFiller.qualifiedEventType()!!, focus, (focusFiller?.targetFiller as? TypedFiller<*>)?.qualifiedTypeStr())
            }
        }
        if (res.firstOrNull { it.frame == HasMore::class.qualifiedName } == null && session.schedule.firstOrNull { it is FrameFiller<*> && it.frame() is HasMore } != null) {
            // check if there is undone HasMore
            res += ExpectedFrame(HasMore::class.qualifiedName!!, "status", HASMORESTATUS)
        }
        return DialogExpectation(res)
    }

    private fun findExpectationByFiller(session: UserSession, filler: IFiller): ExpectedFrame? {
        when (filler) {
            is EntityFiller<*> -> return ExpectedFrame(filler.qualifiedEventType() + (extractSlotType(filler)?.let { "$${it}" } ?: ""), filler.attribute, filler.qualifiedTypeStr())
            is FrameFiller<*> -> {
                val index = session.schedule.indexOf(filler)
                val focusFiller = if (index != -1 && session.schedule.size > index+1) session.schedule[index+1] as? AnnotatedWrapperFiller else null
                return ExpectedFrame(filler.qualifiedEventType()!!, focusFiller?.attribute, (focusFiller?.targetFiller as? TypedFiller<*>)?.qualifiedTypeStr())
            }
            is InterfaceFiller<*> -> {
                val parent = filler.parent?.parent
                if (parent != null) return findExpectationByFiller(session, parent)
            }
            is MultiValueFiller<*> -> {
                when (filler.svType) {
                    MultiValueFiller.SvType.ENTITY -> return ExpectedFrame(filler.qualifiedEventType()!!, filler.attribute, filler.qualifiedTypeStrForSv())
                    MultiValueFiller.SvType.FRAME -> return ExpectedFrame(filler.qualifiedEventType()!!)
                    MultiValueFiller.SvType.INTERFACE -> {
                        val frameFiller = filler.parent?.parent as? FrameFiller<*>
                        if (frameFiller != null) {
                            return ExpectedFrame(frameFiller.qualifiedEventType()!!, filler.attribute, filler.qualifiedTypeStrForSv())
                        }
                    }
                }
            }
        }
        return null
    }

    // extract ValueClarification slot type
    private fun extractSlotType(filler: EntityFiller<*>): String? {
        val clarifyFrame = (filler.parent?.parent as? FrameFiller<*>)?.frame() as? AbstractValueClarification<*>
        if (clarifyFrame != null) {
            val target = clarifyFrame.targetFrame
            val slot = clarifyFrame.slot
            val propertyType = target::class.memberProperties.firstOrNull { it.name == slot }?.returnType?.toString()
            if (propertyType != null) {
                return if (propertyType.endsWith("?")) propertyType.substring(0, propertyType.length-1) else propertyType
            }
        }
        return null
    }

    fun convertSpecialFrameEvent(session: UserSession, events: List<FrameEvent>): List<FrameEvent> {
        if (session.schedule.isNotEmpty()) {
            if (events.size == 1 && events.first().slots.isEmpty() && session.isOpenIntent(events.first())) {
                val frame = ((session.schedule.firstOrNull { it is AnnotatedWrapperFiller && it.targetFiller is FrameFiller<*> && it.targetFiller.frame()::class.qualifiedName == events.first().fullType } as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.frame()
                if (frame != null) {
                    val fullResumeIntent = SystemAnnotationType.ResumeIntent.typeName
                    val packageName = fullResumeIntent.substringBeforeLast(".")
                    val type = fullResumeIntent.substringAfterLast(".")
                    return listOf(FrameEvent(type, packageName = packageName).apply { slotAssignments["intent"] = {frame} })
                }
            } else {
                var pagedSelectableFiller: FrameFiller<*>? = null
                val lastFrameFiller = session.schedule.lastIsInstanceOrNull<FrameFiller<*>>()
                if (lastFrameFiller != null && lastFrameFiller.frame() is PagedSelectable<*> && events.firstOrNull { it.type == "PagedSelectable" } == null) {
                    pagedSelectableFiller = lastFrameFiller
                } else if (lastFrameFiller != null && lastFrameFiller.frame() is Confirmation && events.firstOrNull { (it.type == "Yes" || it.type == "No") && it.packageName == CONFIRMATIONPACKAGE } == null) {
                    val activeFrameFillers = session.schedule.filter { it is FrameFiller<*> }
                    if (activeFrameFillers.size > 1) {
                        val potentialPagedSelectableFiller = activeFrameFillers[activeFrameFillers.size - 2] as? FrameFiller<*>
                        if (potentialPagedSelectableFiller?.frame() is PagedSelectable<*>) {
                            pagedSelectableFiller = potentialPagedSelectableFiller
                        }
                    }
                }
                if (pagedSelectableFiller != null) {
                    val recFrame: PagedSelectable<*> = pagedSelectableFiller.frame() as PagedSelectable<*>
                    val targetFrame = recFrame.target ?: return events
                    val frameWrapperFiller = session.findWrapperFillerWithFrame(targetFrame) ?: return events
                    val childSlot = recFrame.slot
                    val targetFiller = if (childSlot.isNullOrEmpty()) {
                        frameWrapperFiller
                    } else {
                        (frameWrapperFiller.targetFiller as? FrameFiller<*>)?.fillers?.get(childSlot) ?: return events
                    }

                    val res: MutableList<FrameEvent> = mutableListOf()
                    for (event in events) {
                        if (targetFiller.isCompatible(event.apply { source = EventSource.USER })) {
                            val nonFunctionCallSlotEvents: MutableList<EntityEvent> = mutableListOf()
                            val classArrNode = ArrayNode(JsonNodeFactory.instance, listOf(TextNode(event.fullType)))
                            val objNode = ObjectNode(JsonNodeFactory.instance)
                            objNode.replace("@class", classArrNode)
                            for (entityEvent in event.slots) {
                                if (targetFiller.isCompatible(FrameEvent(event.type, slots = listOf(entityEvent), packageName = event.packageName).apply { source = EventSource.USER })) {
                                    if (session.chatbot!!.stateTracker.isPartialMatch(entityEvent)) {
                                        val candidateValues = session.chatbot!!.stateTracker.findRelatedEntity(entityEvent)?.map { TextNode(it) }
                                        if (!candidateValues.isNullOrEmpty()) {
                                            objNode.replace(entityEvent.attribute, ArrayNode(JsonNodeFactory.instance, candidateValues))

                                        }
                                    } else {
                                        val arrNode = ArrayNode(JsonNodeFactory.instance, listOf(Json.parseToJsonElement(entityEvent.value)))
                                        objNode.replace(entityEvent.attribute, arrNode)
                                    }
                                } else {
                                    nonFunctionCallSlotEvents += entityEvent
                                }
                            }
                            res += FrameEvent(FilterCandidate::class.simpleName!!, slots = listOf(EntityEvent(TextNode(objNode.toString()).toString(), "conditionMapJson")), packageName = FilterCandidate::class.qualifiedName!!.substringBeforeLast("."))
                            if (nonFunctionCallSlotEvents.isNotEmpty()) {
                                res += FrameEvent(event.type, slots = nonFunctionCallSlotEvents, packageName = event.packageName)
                            }
                        } else {
                            res += event
                        }
                    }
                    return res
                }
            }
        }
        return events
    }
}