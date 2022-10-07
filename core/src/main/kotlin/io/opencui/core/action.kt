package io.opencui.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.opencui.core.da.ComponentDialogAct
import io.opencui.serialization.*
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlin.math.min
import kotlin.reflect.full.primaryConstructor

/**
 * There should be two concepts here: the bot utterance for one channel, and mapped utterance for multiple channels.
 * and only the map utterance need the get utterance with channel as input parameter.
 */
typealias ChannelType = String

// isTestable controls whether this log will participate in the log comparison during testing.
data class ActionLog(
    val type: String,
    val payload: JsonElement,
    @JsonIgnore val isTestable: Boolean = false) : Serializable

data class ActionResult(
    val actionLog: ActionLog?, 
    val success: Boolean = true) : Serializable {
    var botOwn: Boolean  = true
    var botUtterance: List<ComponentDialogAct>? = null
    constructor(b: List<ComponentDialogAct>?, a: ActionLog?, s: Boolean = true) : this(a, s) {
        botUtterance = b
    }
}


/**
 * SideEffect means that action will render message to user, all the response will be doing that.
 */
interface SideEffect {
    companion object {
        const val RESTFUL = "restful"
    }
}

/**
 * Action is used for many things:
 * 1. build statechart,
 * 2. state change/direct assignment
 * 3. and as well as side effect that emitted to
 * 4. generate event (feel this is indirect state change).
 * end user so that user knows what to expect. Response of state machine does not change state.
 */
interface Action: Serializable {
    fun run(session: UserSession): ActionResult
}

// This should only be executed in kernel mode.
interface KernelMode

interface AtomAction : Action

interface ChartAction : AtomAction

// Ideally we should be enter and exit state declared in the interface so that it is easy to check.
interface StateAction : AtomAction

interface SchemaAction: AtomAction


// There are different composite actions, easy ones are list.
interface CompositeAction : Action {
    //fun runs(): ActionResults
}


fun Action.emptyResult() : ActionResult {
    return ActionResult(emptyLog())
}

fun Action.emptyLog() : ActionLog {
    return ActionLog(this::class.java.simpleName, Json.makePrimitive(""), false)
}

fun Action.createLog(payload: String): ActionLog {
    return createLog(Json.makePrimitive(payload))
}

fun Action.createLog(payload: JsonElement): ActionLog {
    return ActionLog(this::class.java.simpleName, payload, true)
}

fun Action.createNoTypeLog(payload: JsonElement): ActionLog {
    return ActionLog("", payload, true)
}



/**
 * This action is used when focus = null, but input != null, have a target. The key should
 * be two things:
 * 1. reset the session focus.
 * 2. update the filling scheduler so that we know want to do.
 */
data class StartFill(
    val match: FrameEvent,
    val buildIntent: IFrameBuilder,
    val label: String
) : ChartAction, KernelMode {
    // override val type = ActionType.CHART
    override fun run(session: UserSession): ActionResult {
        // start every action with consuming corresponding events
        match.triggered = true
        match.typeUsed = true

        val intent = buildIntent.invoke(session) ?: return ActionResult(emptyLog())
        // Now we know we have intent to fill.
        // we need to set the frame filler, slot filler, and filler schedule before we give up
        // control. Notice we will do two things, if the top filler attribute is not branchable
        // then we use send the frame at intent level, if it is, then we set the top one to be
        // it, and then adjust the stack.
        val filler = intent.createBuilder().invoke(ParamPath(intent)) as FrameFiller<*>
        val wrapperFiller = AnnotatedWrapperFiller(filler)
        filler.parent = wrapperFiller

        // init if needed
        if (buildIntent is FullFrameBuilder) {
            buildIntent.init(session, filler)
        }

        if (session.inKernelMode(session.schedule)) return ActionResult(emptyLog())

        if (intent.type == FrameKind.BIGINTENT) {
            with(session) {
                while (schedulers.size > 1
                        && ((schedule.firstOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.frame()?.type == FrameKind.SMALLINTENT) {
                    schedulers.removeLast()
                }
                if (((mainSchedule.firstOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.frame()?.type == FrameKind.SMALLINTENT) {
                    mainSchedule.cleanup()
                }
            }
        }
        if (session.schedule.isNotEmpty()) {
            session.schedulers += Scheduler(session)
        }
        session.schedule.push(wrapperFiller)

        // TODO(xiaobo): What is the better place for this?
        // assume we know SlotUpdate has slots named "originalSlot", "oldValue" and "newValue"
        if (intent is AbstractSlotUpdate<*> && match.slots.firstOrNull { it.attribute == "originalSlot" && !it.isUsed} == null) {
            val candidates = mutableListOf<SlotType>()
            val t = findValueAndType(match)
            if (t != null) {
                val value = t.first
                val types = t.second.toSet()
                val index = t.third
                val candidateFillers = mutableListOf<AnnotatedWrapperFiller>()
                var topFiller = session.mainSchedule.firstOrNull() as? AnnotatedWrapperFiller
                if (((topFiller?.targetFiller as? FrameFiller<*>)?.fillers?.get("skills")?.targetFiller as? MultiValueFiller<*>)?.findCurrentFiller() != null) {
                    topFiller = ((topFiller.targetFiller as FrameFiller<*>).fillers["skills"]!!.targetFiller as MultiValueFiller<*>).findCurrentFiller()
                }
                session.findFillers(topFiller, candidateFillers, { f ->
                    index == null
                        && f.targetFiller is EntityFiller<*>
                        && types.contains(f.targetFiller.qualifiedTypeStr())
                        && f.done()
                        && (value == "" || value == Json.encodeToString(f.targetFiller.target.get()!!))
                }, additionalBaseCase = { f ->
                    // never enter entity mv slot filler
                    f.targetFiller is MultiValueFiller<*> && f.targetFiller.svType == MultiValueFiller.SvType.ENTITY
                })
                session.findFillers(topFiller, candidateFillers, { f ->
                    val fillerAgree = f.targetFiller is MultiValueFiller<*> && f.targetFiller.svType == MultiValueFiller.SvType.ENTITY
                    if (!fillerAgree) return@findFillers false
                    check(f.targetFiller is MultiValueFiller<*>)
                    val typeAgree = types.isEmpty() || types.contains(f.targetFiller.qualifiedTypeStrForSv())
                    if (!typeAgree) return@findFillers false
                    val indexAgree = index == null || (f.targetFiller.fillers.size >= index && f.targetFiller.fillers[index-1].done())
                    if (!indexAgree) return@findFillers false
                    if (value == "") {
                        f.targetFiller.fillers.any { it.done() }
                    } else {
                        if (index == null) f.targetFiller.fillers.filter { it.done() }.map { Json.encodeToString((it.targetFiller as TypedFiller<*>).target.get()!!) }.contains(value)
                        else Json.encodeToString((f.targetFiller.fillers[index-1].targetFiller as TypedFiller<*>).target.get()!!) == value
                    }
                })
                candidates.addAll(candidateFillers.map { it.path!!.path.last().let { p -> if (p.attribute == "this") p.frame::class.qualifiedName!! else "${p.frame::class.qualifiedName!!}.${p.attribute}" } }.map { SlotType(it).apply { this.session = session } }.toSet())
            }

            if (candidates.isEmpty()) {
                // do nothing and let SlotUpdate handle it
                // prevent slot events to take effect in the following turns
                match.slots.forEach { it.isUsed = true }
            } else if (candidates.size == 1) {
                val frameEventList = session.generateFrameEvent(filler.fillers["originalSlot"]!!.targetFiller, candidates.first())
                if (frameEventList.isNotEmpty()) session.addEvents(frameEventList)
            } else {
                val clarificationClass = SystemAnnotationType.ValueClarification.typeName

                val buildIntent = intentBuilder(
                    FrameEvent(clarificationClass.substringAfterLast("."), packageName = clarificationClass.substringBeforeLast(".", ""))
                        .apply { triggerParameters.addAll(listOf({ SlotType::class }, candidates, intent, "originalSlot"))}
                    )

                val clarificationIntent = buildIntent.invoke(session)!!
                val clarificationFiller = clarificationIntent.createBuilder().invoke(ParamPath(clarificationIntent)) as FrameFiller<*>
                val clarificationWrapperFiller = AnnotatedWrapperFiller(clarificationFiller)
                clarificationFiller.parent = clarificationWrapperFiller
                session.schedulers += Scheduler(session)
                session.schedule.push(clarificationWrapperFiller)
            }
        }

        // Now we get schedule ready.
        return RescheduleAction().run(session)
    }

    fun findValueAndType(match: FrameEvent): Triple<String, List<String>, Int?>? {
        val index = match.slots.firstOrNull { it.attribute == "index" }?.value?.let { Json.decodeFromString<Ordinal>(it) }?.value?.toInt()
        val oldValueSlot = match.slots.firstOrNull { it.attribute == "oldValue" }
        if (oldValueSlot != null) {
            val oldValueTypeCandidates = match.frames.firstOrNull { it.slots.firstOrNull { it.attribute == "oldValue" } != null }?.slots?.map { it.type!! }
            return Triple(oldValueSlot.value, oldValueTypeCandidates ?: listOf(oldValueSlot.type!!), index)
        }
        val newValueSlot = match.slots.firstOrNull { it.attribute == "newValue" }
        if (newValueSlot != null) {
            val newValueTypeCandidates = match.frames.firstOrNull { it.slots.firstOrNull { it.attribute == "newValue" } != null }?.slots?.map { it.type!! }
            return Triple("", newValueTypeCandidates ?: listOf(newValueSlot.type!!), index)
        }
        return if (index == null) null else Triple("", listOf(), index)
    }
}

data class SimpleFillAction(
        val filler: AEntityFiller,
        var match: FrameEvent
) : StateAction {
    override fun run(session: UserSession): ActionResult {
        var finalMatch = match
        // commit is responsible for marking the part of FrameEvent that it used
        val success = filler.commit(finalMatch)
        if (!success) return ActionResult(null, false)

        session.schedule.state = Scheduler.State.RESCHEDULE
        return ActionResult(null)
    }
}

data class RefocusActionBySlot(
        val frame: IFrame, val slot: String?
) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val path = session.findActiveFillerPathForTargetSlot(frame, slot)
        return if (path.isEmpty()) ActionResult(null, true) else RefocusAction(path as List<ICompositeFiller>).run(session)
    }
}

data class RefocusAction(
    val refocusPath: List<IFiller>
) : StateAction {
    override fun run(session: UserSession): ActionResult {
        var scheduler: Scheduler? = null
        for (s in session.schedulers) {
            if (refocusPath.isNotEmpty() && refocusPath.first() == s.firstOrNull()) {
                scheduler = s
                break
            }
        }

        if (scheduler == null) return ActionResult(emptyLog())

        val startList = refocusPath.filterIsInstance<AnnotatedWrapperFiller>()
        val start = startList.withIndex().first { it.value.isSlot && (it.index == startList.size-1 || !startList[it.index+1].isSlot) }.value
        val endList = scheduler.filterIsInstance<AnnotatedWrapperFiller>()
        val end = endList.withIndex().first { it.value.isSlot && (it.index == endList.size-1 || !endList[it.index+1].isSlot) }.value

        var divergeIndex = 0
        for (i in 0 until min(scheduler.size, refocusPath.size)) {
            if (scheduler[i] != refocusPath[i]) {
                divergeIndex = i
                break
            }
        }

        for (i in scheduler.size - 1 downTo divergeIndex) {
            scheduler.pop()
        }

        for (i in divergeIndex until refocusPath.size) {
            scheduler.push(refocusPath[i])
        }

        // set slots in between to recheck state; in post order
        session.postOrderManipulation(scheduler, start, end) { it.recheck() }

        // assume we have a candidate value for the refocused slot or we want to ask for the refocused slot
        if (scheduler == session.schedule) {
            RescheduleAction().run(session)
        } else {
            scheduler.state = Scheduler.State.RESCHEDULE
        }
        return ActionResult(emptyLog())
    }
}

data class RecoverAction(val tag: String = "") : StateAction {
    override fun run(session: UserSession): ActionResult {
        session.schedule.state = Scheduler.State.RESCHEDULE
        return ActionResult(emptyLog())
    }
}


data class SlotAskAction(val tag: String = "") : StateAction {
    override fun run(session: UserSession): ActionResult {
        val filler = session.schedule.last()
        val debugInfo = session.userIdentifier.channelId() == SideEffect.RESTFUL
        // decorative prompts from outer targets of VR, prompt only once
        val vrTargetPrompts = mutableListOf<List<Action>>()
        for (f in session.schedule) {
            if (f == (f.parent as? AnnotatedWrapperFiller)?.recommendationFiller) {
                val vrTargetPromptAnnotation = f.decorativeAnnotations.firstIsInstanceOrNull<PromptAnnotation>()
                if (vrTargetPromptAnnotation != null) {
                    val ancestorVRTargetActions = vrTargetPromptAnnotation.actions
                    vrTargetPrompts += ancestorVRTargetActions
                    f.decorativeAnnotations.remove(vrTargetPromptAnnotation)
                }
            }
        }

        // current slot prompt
        val currentTemplates = filler.slotAskAnnotation()!!.actions

        val potentialPagedSelectableFiller = filler.parent?.parent as? FrameFiller<*>
        // whether we are asking index from PagedSelectable(VR)
        val inVR = potentialPagedSelectableFiller?.frame() is PagedSelectable<*>
        // vr target prompt, if there is one
        val directlyVRTargetPrompt = if (inVR) {
            potentialPagedSelectableFiller?.parent?.parent?.slotAskAnnotation()?.actions
        } else {
            null
        }

        val actions = mutableListOf<List<Action>>()
        if (vrTargetPrompts.isNotEmpty()) {
            actions += vrTargetPrompts.first()
        } else if (directlyVRTargetPrompt != null) {
            actions += directlyVRTargetPrompt
        }
        actions += currentTemplates
        val flatActions = actions.flatten()

        session.schedule.state = Scheduler.State.POST_ASK
        val res = if (flatActions.size == 1) flatActions[0].run(session) else SeqAction(flatActions).run(session)
        val actionLog = if (res.actionLog != null) {
            if (res.actionLog.payload is ArrayNode) {
                createLog(res.actionLog.payload.filterIsInstance<ObjectNode>().joinToString("\n") { it["payload"].textValue() })
            } else {
                createLog(res.actionLog.payload)
            }
        } else {
            null
        }
        return ActionResult(res.botUtterance, actionLog, res.success)
    }
}

data class SlotPostAskAction(
    val filler: IFiller,
    var match: FrameEvent
) : StateAction {
    private fun goback(session: UserSession): ActionResult {
        // we need to go back the ask again.
        session.schedule.state = Scheduler.State.ASK
        val delegateActionResult = session.findSystemAnnotation(SystemAnnotationType.IDonotGetIt)?.searchResponse()?.run(session)
        return delegateActionResult ?: ActionResult(emptyLog())
    }

    override fun run(session: UserSession): ActionResult {
        if (filler is AEntityFiller) {
            var finalMatch = match
            val related = match.slots.find { it.attribute == filler.attribute && !it.isUsed }
            if (related != null && related.value == "\"_context\"") {
                related.isUsed = true
                val className = (filler as TypedFiller<*>).qualifiedTypeStr()
                val fromContexts = session.searchContext(listOf(className))
                val wrapperFiller = session.schedule.filterIsInstance<AnnotatedWrapperFiller>().last { it.targetFiller is FrameFiller<*> }
                val attr = filler.attribute
                if (fromContexts.isEmpty()) {
                    session.schedule.state = Scheduler.State.ASK
                    return ActionResult(emptyLog())
                } else if (fromContexts.size == 1) {
                    val targetFiller = if (attr.isEmpty() || attr == "this") wrapperFiller else (wrapperFiller.targetFiller as FrameFiller<*>).fillers[attr]!!
                    val frameEventList = session.generateFrameEvent(targetFiller.targetFiller, fromContexts.first())
                    if (frameEventList.size == 1) finalMatch = frameEventList.first()
                } else {
                    try {
                        // TODO(xiaobo) can you move this out?
                        val kClass = { session.findKClass(className)!! }
                        val clarificationClass = SystemAnnotationType.ValueClarification.typeName
                        val simpleName = clarificationClass.substringAfterLast(".")
                        val packageName = clarificationClass.substringBeforeLast(".", "")
                        val triggerEvent = FrameEvent(simpleName, packageName = packageName).apply {
                            triggerParameters.addAll(listOf(kClass , fromContexts.toSet().toMutableList(), (wrapperFiller.targetFiller as FrameFiller<*>).frame(), attr))
                        }
                        session.addEvent(triggerEvent)
                        return ActionResult(emptyLog())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        session.schedule.state = Scheduler.State.ASK
                        return ActionResult(emptyLog())
                    }
                }
            }

            // commit is responsible for marking the part of FrameEvent that it used
            val success = filler.commit(finalMatch)
            if (!success) return goback(session)
        } else {
            // assume that filler is able to consume the event
            val success = (filler as Committable).commit(match)
            if (!success) return goback(session)
        }

        return RescheduleAction().run(session)
    }
}

// temporarily absorb SlotDone into SlotPostAsk and SimpleFill. need to refactor SlotDone
class SlotDoneAction(val filler: AnnotatedWrapperFiller) : StateAction {
    override fun run(session: UserSession): ActionResult {
        session.schedule.state = Scheduler.State.RESCHEDULE
        val slotDoneAnnotations = filler.path!!.findAll<SlotDoneAnnotation>()
        val actions = slotDoneAnnotations.filter { it.condition() }.flatMap { it.actions }
        if (actions.isNotEmpty()) {
            return SeqAction(actions).run(session)
        }
        return ActionResult(null)
    }
}

class RespondAction : CompositeAction {
    override fun run(session: UserSession): ActionResult {
        val wrapperFiller = session.schedule.lastOrNull() as? AnnotatedWrapperFiller
        val response = ((wrapperFiller?.targetFiller as? FrameFiller<*>)?.frame() as? IIntent)?.searchResponse()
        val res = if (response != null) {
            val tmp = response.run(session)
            if (!tmp.success) throw Exception("fail to respond!!!")
            tmp
        } else {
            null
        }
        wrapperFiller!!.responseDone = true
        session.schedule.state = Scheduler.State.RESCHEDULE
        return res ?: ActionResult(emptyLog())
    }
}

class RescheduleAction : StateAction {
    override fun run(session: UserSession): ActionResult {
        val schedule = session.schedule
        while (schedule.size > 0) {
            val top = schedule.peek()
            // if ancestor is marked done, consider the ICompositeFiller done
            val topDone = top.done(session.activeEvents) || (top !is AnnotatedWrapperFiller && (top.parent as? AnnotatedWrapperFiller)?.done(session.activeEvents) == true)
            if (topDone) {
                val doneFiller = schedule.pop()
                if (schedule.isEmpty()) {
                    session.finishedIntentFiller += doneFiller as AnnotatedWrapperFiller
                }
                if (doneFiller is AnnotatedWrapperFiller && doneFiller.isSlot) {
                    return SlotDoneAction(doneFiller).run(session)
                }
            } else {
                break
            }
        }

        if (schedule.isNotEmpty()) {
            val grown = schedule.grow()
            check(grown)
        } else {
            session.schedule.state = Scheduler.State.INIT
        }

        return ActionResult(emptyLog())
    }
}

// TODO(xiaobo): can we remove this now?
abstract class ExternalAction(
    open val frame: IFrame,
    private vararg val args: Any?
) : CompositeAction {
    override fun run(session: UserSession): ActionResult {
        try {
            val kClass = Class.forName("${packageName}.${actionName}", true, javaClass.classLoader).kotlin
            val ctor = kClass.primaryConstructor
            val action = ctor?.call(session, *args) as? Action
            val result = action?.run(session)
            if (result != null) {
                val jsonArrayLog = mutableListOf<JsonElement>()
                jsonArrayLog.add(Json.makePrimitive("EXTERNAL ACTION : ${action.javaClass.name}"))
                if (result.actionLog != null) {
                    jsonArrayLog.add(Json.encodeToJsonElement(result.actionLog))
                }
                return ActionResult(result.botUtterance, createLog(Json.makeArray(jsonArrayLog)), true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: Error) {
            e.printStackTrace()
        }

        return ActionResult(
            createLog("EXTERNAL ACTION cannot construct action : ${packageName}.${actionName}"),
            false
        )
    }

    abstract val packageName: String
    abstract val actionName: String
}

data class IntentAction(
    val jsonIntent: FullFrameBuilder
) : ChartAction, KernelMode {
    override fun run(session: UserSession): ActionResult {
        val intent = jsonIntent.invoke(session)?: return ActionResult(createLog("INTENT ACTION cannot construct intent : $jsonIntent"), false)

        val intentFillerBuilder = intent.createBuilder()
        val currentFiller = session.schedule.lastOrNull() as? FrameFiller<*>

        val targetFiller = intentFillerBuilder.invoke(if (currentFiller != null) currentFiller.path!!.join("_action", intent) else ParamPath(intent)) as FrameFiller<*>
        val targetFillerWrapper = AnnotatedWrapperFiller(targetFiller)
        targetFiller.parent = targetFillerWrapper
        targetFillerWrapper.parent = currentFiller
        session.schedule.push(targetFillerWrapper)
        jsonIntent.init(session, targetFiller as FrameFiller<*>)
        return ActionResult(createLog("INTENT ACTION : ${intent.javaClass.name}"), true)
    }
}

open class TextOutputAction(
        val dialogActGen: () -> ComponentDialogAct
) : SchemaAction {
    override fun run(session: UserSession): ActionResult {
        val success = true
        val dialogAct = dialogActGen()
        return ActionResult(
                listOf(dialogAct),
                createLog(dialogAct.templates.pick().invoke()),
                success
        )
    }
}

open class TextListOutputAction(
    val dialogActGen: () -> ComponentDialogAct
) : SchemaAction {
    override fun run(session: UserSession): ActionResult {
        val success = true
        val dialogAct = dialogActGen()
        return ActionResult(
            listOf(dialogAct),
            createLog(dialogAct.templates.pick().invoke()),
            success
        )
    }
}

// TODO(xiaoyun): separate execution path for composite action later.
open class SeqAction(val actions: List<Action>): CompositeAction {
    constructor(vararg actions: Action): this(actions.toList())
    override fun run(session: UserSession): ActionResult {
        // TODO(xiaoyun): the message can be different.
        val messages = mutableListOf<ComponentDialogAct>()
        val logs = mutableListOf<ActionLog>()
        var flag = true
        for (action in actions) {
            val result = action.run(session)
            if (result.actionLog != null) {
                logs += result.actionLog
            }
            if (result.botUtterance != null) {
                messages.addAll(result.botUtterance!!)
            }
            flag = flag && result.success
        }

        return ActionResult(
            messages,
            createLog(Json.makeArray(logs.map { l -> Json.encodeToJsonElement(l) })),
            flag)
    }
}

data class LazyPickAction(val picker: ()->Action): SchemaAction {
    override fun run(session: UserSession): ActionResult {
        return picker().run(session)
    }
}
class Handoff: SchemaAction {
    fun matchSize(intentStr: String, routingInfo:RoutingInfo) : Int {
        var maxSize : Int = 0
        for (partial in routingInfo.intentsDesc) {
            if (intentStr.startsWith(partial.lowercase())) {
                val size = partial.split(".").size
                if (size > maxSize) maxSize = size
            }
        }
        return maxSize
    }

    fun findDepartment(intentStr: String?, routing: Map<String, RoutingInfo>) : String {
        if (intentStr == null) return routing[DEFAULT]!!.id
        val list = mutableListOf<Pair<String, Int>>()
        for ( (k, v) in routing) {
            val size = matchSize(intentStr, v)
            if (size != 0) {
                list.add(Pair(v.id, size))
            }
        }
        return if (list.size == 0) {
            routing[DEFAULT]!!.id
        } else {
            list.sortByDescending { it.second }
            list[0].first
        }
    }

    override fun run(session: UserSession): ActionResult {
        var intentStr = session.getOpenPayloadIntent()?.lowercase()
        logger.info("Hand off session for ${session.userIdentifier} for $intentStr")
        val department = findDepartment(intentStr, session.chatbot!!.routing)
        Dispatcher.handOffSession(session.userIdentifier, session.botInfo, department)
        return ActionResult(createLog("HandOff"), true)
    }

    companion object {
        val logger = LoggerFactory.getLogger(Handoff::class.java)
        const val DEFAULT = "Default"
    }
}