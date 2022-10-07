package io.opencui.core

import io.opencui.core.hasMore.IStatus
import io.opencui.core.hasMore.Yes
import kotlin.math.min
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.hasMore.No
import io.opencui.core.Dispatcher.closeSession
import io.opencui.core.da.ComponentDialogAct
import io.opencui.serialization.Json
import java.io.Serializable
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KProperty1





/**
 * this file contains the things we need from platform, they will be declared on platform so that they can
 * referenced. The declaration on the platform need not to be complete, but need to be accurate so the
 * reference is always available.
 * TODO: the IFrame that builder can define and use should only have one constructor parameter with session.
 * The special intent like clarification can only be started by bot, so we just need to create
 * specially IIntentBuilder for them.
 */

// One of the main problem, we sometime need to create frame and frame filler together so that filler
// knows what is filled? Can we just use the nullness as indicator for filler to understand what is done?
// Maybe, but need to change the logic to some extent.

// Use this instead of another function for (UserSession) -> IIntent to avoid the overloading ambiguity
// Kotlin treat different all function as functions.
fun interface IFrameBuilder : Serializable {
    fun invoke(session: UserSession): IFrame?
}

fun interface InitIFrameFiller {
    fun init(session: UserSession, filler: FrameFiller<*>)
}

interface FullFrameBuilder: IFrameBuilder, InitIFrameFiller, Serializable {
    override fun init(session: UserSession, filler: FrameFiller<*>) {}
}

data class JsonFrameBuilder(
    @get:JsonIgnore val value: String,
    @JsonIgnore val constructorParameters: List<Any?> = listOf(),
    @JsonIgnore var slotAssignments: Map<String, ()->Any?> = mapOf()
): FullFrameBuilder {
    override fun invoke(session: UserSession) : IFrame? {
        val objectNode = Json.parseToJsonElement(value) as ObjectNode
        val fullyQualifiedName = objectNode.get("@class").asText()
        val packageName = fullyQualifiedName.substringBeforeLast(".")
        val intentName = fullyQualifiedName.substringAfterLast(".")
        return session.construct(packageName, intentName, *constructorParameters.toTypedArray())
    }

    override fun init(session: UserSession, filler: FrameFiller<*>) {
        val jsonString = value
        val jsonObject = Json.parseToJsonElement(jsonString) as ObjectNode
        val className = jsonObject.remove("@class").asText()
        val kClass = session.findKClass(className)!!
        val frame: IFrame = Json.decodeFromJsonElement(jsonObject, kClass) as IFrame
        frame.session = session
        for (k in jsonObject.fieldNames()) {
            val f = filler.fillers[k] ?: continue
            val property = kClass.memberProperties.firstOrNull { it.name == k } as? KProperty1<Any, Any> ?: continue
            val v = property.get(frame)
            f.directlyFill(v)
        }
        for ((k, vg) in slotAssignments) {
            val f = filler.fillers[k] ?: continue
            val v = vg() ?: continue
            f.directlyFill(v)
        }
    }
}

data class EventFrameBuilder(
    val frameEvent: FrameEvent
) : FullFrameBuilder {
    override fun invoke(session: UserSession): IFrame? {
        val ib = intentBuilder(frameEvent)
        return ib.invoke(session)
    }

    override fun init(session: UserSession, filler: FrameFiller<*>) {
        for ((k, vg) in frameEvent.slotAssignments) {
            val f = filler.fillers[k] ?: continue
            val v = vg() ?: continue
            f.directlyFill(v)
        }
    }
}

fun intentBuilder(frameEvent: FrameEvent): IFrameBuilder {
    val type = frameEvent.type
    val packageName = frameEvent.packageName
    return IFrameBuilder{ session -> session.construct(packageName, type, session, *frameEvent.triggerParameters.toTypedArray()) as? IIntent }
}

fun intentBuilder(fullyQualifiedName: String, vararg args: Any?): IFrameBuilder {
    return IFrameBuilder{
        session ->
            if (fullyQualifiedName.isEmpty() || fullyQualifiedName.lastIndexOf(".") < 0 ) {
                null
            }  else {
                val index = fullyQualifiedName.lastIndexOf(".")
                val packageName = fullyQualifiedName.substring(0, index)
                val className = fullyQualifiedName.substring(index + 1)
                session.construct(packageName, className, session, *args) as? IIntent
            }
    }
}

fun <T:IFrame> intentBuilder(eventFrame: T, updateRules: List<UpdateRule>):IFrameBuilder {
    return IFrameBuilder{ session -> StitchedIntent<T>(session, eventFrame, updateRules) }
}


data class HasMore(
        override var session: UserSession? = null,
        val promptActions: List<Action>,
        val inferFunc: (FrameEvent) -> FrameEvent?,
        val minChecker: () -> Boolean = {true},
        val minPrompts: () -> ComponentDialogAct
) : IFrame {

    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    var status: IStatus? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mapOf(
        STATUS to listOf(
            SlotPromptAnnotation(promptActions),
            ValueCheckAnnotation(OldValueCheck(session, { status !is No || minChecker()}, listOf(Pair(this, STATUS)), minPrompts)))
    )

    fun hasMore(): Boolean {
        return status is Yes
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: HasMore? = this@HasMore
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<HasMore?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, ISTATUS), inferFunc))
            return filler
        }
    }

    companion object {
        val ISTATUS = IStatus::class.qualifiedName!!
        const val STATUS = "status"
    }
}

data class BoolGate(
    override var session: UserSession? = null,
    val prompts: () -> ComponentDialogAct,
    val inferFunc: (FrameEvent) -> FrameEvent?) : IFrame {

    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    var status: io.opencui.core.booleanGate.IStatus? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mapOf(
        "status" to listOf(SlotPromptAnnotation(listOf(TextOutputAction(prompts))))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: BoolGate? = this@BoolGate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<BoolGate?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.booleanGate.IStatus::class.qualifiedName!!), inferFunc))
            return filler
        }
    }
}

class CloseSession() : ChartAction {
    override fun run(session: UserSession): ActionResult {
        // next time the same channel/id is call, we need a new UserSession.
        // so that botOwn is true.
        closeSession(session.userIdentifier, session.botInfo)
        session.cleanup()
        return ActionResult(createLog("CLEAN SESSION"), true)
    }
}

enum class FillStateEnum {
    SlotInit
}

data class ActionWrapperIntent(override var session: UserSession? = null, val action: Action): IIntent {
    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: ActionWrapperIntent? = this@ActionWrapperIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<ActionWrapperIntent?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return action
    }
}

//TODO(xiaobo): have a condition is good, but it will be more powerful if it is outside of list.
data class UpdateRule(val condition: () -> Boolean, val action: Action, val score: Int = 0) : Serializable


/**
 * Dynamically created intent from the event frame T and update rules.
 */
data class StitchedIntent<T: IFrame>(
    override var session: UserSession? = null,
    val eventFrame: T,
    val updateRules: List<UpdateRule>): IIntent {

    override val type = FrameKind.SMALLINTENT
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: StitchedIntent<T>? = this@StitchedIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<StitchedIntent<T>?> ?: ::frame
            val eventFrame = eventFrame
            val filler = FrameFiller({ tp }, path)
            filler.add(eventFrame.createBuilder().invoke(path.join("eventFrame", eventFrame)) as FrameFiller<*>)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        val filteredUpdates = updateRules.filter { it.condition() }
        // TODO(xiaobo): why do we need this score?
        val maxScore = filteredUpdates.map { it.score }.maxOrNull()
        val updateActions = filteredUpdates.filter { it.score == maxScore }.map { it.action }
        return when {
            else -> SeqAction(updateActions)
        }
    }
}


data class CleanupAction(
    val toBeCleaned: List<IFiller>
) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (fillerToBeCleaned in toBeCleaned) {
            fillerToBeCleaned.clear()
            for (currentScheduler in session.schedulers.reversed()) {
                var index = currentScheduler.size
                for ((i, f) in currentScheduler.withIndex()) {
                    if (f == fillerToBeCleaned) {
                        index = i
                        break
                    }
                }
                // pop fillers that have gone back to initial state but never pop the root filler in a CleanupAction
                if (index < currentScheduler.size) {
                    var count = currentScheduler.size - index
                    while (count-- > 0 && currentScheduler.size > 1) {
                        currentScheduler.pop()
                    }
                    break
                }
            }
        }

        return ActionResult(
            createLog("CLEANUP SLOT : ${toBeCleaned.map { it.path!!.path.last() }.joinToString { "target=${it.frame.javaClass.name}&slot=${if (it.attribute == "this") "" else it.attribute}" }}"),
            true
        )
    }
}

data class CleanupActionBySlot(val toBeCleaned: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeCleaned = mutableListOf<IFiller>()
        for (slotToBeCleaned in toBeCleaned) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slotToBeCleaned.first, slotToBeCleaned.second) ?: continue
            fillersToBeCleaned += targetFiller
        }

        return CleanupAction(fillersToBeCleaned).run(session)
    }
}

data class RecheckAction(val toBeRechecked: List<IFiller>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (fillerToBeRechecked in toBeRechecked) {
            (fillerToBeRechecked as? AnnotatedWrapperFiller)?.recheck()
        }

        return ActionResult(
            createLog("RECHECK SLOT : ${toBeRechecked.map { it.path!!.path.last() }.joinToString { "target=${it.frame.javaClass.name}&slot=${if (it.attribute == "this") "" else it.attribute}" }}"),
            true
        )
    }
}

data class RecheckActionBySlot(val toBeRechecked: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeRechecked = mutableListOf<AnnotatedWrapperFiller>()
        for (slotToBeCleaned in toBeRechecked) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slotToBeCleaned.first, slotToBeCleaned.second) ?: continue
            fillersToBeRechecked += targetFiller
        }

        return RecheckAction(fillersToBeRechecked).run(session)
    }
}

data class ReinitAction(val toBeReinit: List<IFiller>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        for (filler in toBeReinit) {
            (filler as? AnnotatedWrapperFiller)?.reinit()
        }

        return ActionResult(
            createLog("REINIT SLOT : ${toBeReinit.map { it.path!!.path.last() }.joinToString { "target=${it.frame.javaClass.name}&slot=${if (it.attribute == "this") "" else it.attribute}" }}"),
            true
        )
    }
}

data class ReinitActionBySlot(val toBeRechecked: List<Pair<IFrame, String?>>) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val fillersToBeReinit = mutableListOf<AnnotatedWrapperFiller>()
        for (slot in toBeRechecked) {
            val targetFiller = session.findWrapperFillerForTargetSlot(slot.first, slot.second) ?: continue
            fillersToBeReinit += targetFiller
        }

        return ReinitAction(fillersToBeReinit).run(session)
    }
}

data class DirectlyFillAction<T>(
    val generator: () -> T?,
    val filler: AnnotatedWrapperFiller, val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val param = filler.path!!.path.last()
        val value = generator() ?: return ActionResult(
            createLog("FILL SLOT value is null for target : ${param.frame::class.qualifiedName}, slot : ${if (param.attribute == "this") "" else param.attribute}"),
            true
        )
        filler.directlyFill(value)
        filler.decorativeAnnotations.addAll(decorativeAnnotations)
        return ActionResult(
            createLog("FILL SLOT for target : ${param.frame::class.qualifiedName}, slot : ${if (param.attribute == "this") "" else param.attribute}"),
            true
        )
    }
}

data class DirectlyFillActionBySlot<T>(
    val generator: () -> T?,
    val frame: IFrame?,
    val slot: String?,
    val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}, slot : ${slot}"),
            true
        )
        return DirectlyFillAction(generator, wrapFiller, decorativeAnnotations).run(session)
    }
}

data class FillAction<T>(
    val generator: () -> T?,
    val filler: IFiller, val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val param = filler.path!!.path.last()
        val value = generator() ?: return ActionResult(
            createLog("FILL SLOT value is null for target : ${param.frame::class.qualifiedName}, slot : ${if (param.attribute == "this") "" else param.attribute}"),
            true
        )
        val frameEventList = session.generateFrameEvent(filler, value)
        frameEventList.forEach {
            it.triggered = true
            it.slots.forEach { slot ->
                slot.decorativeAnnotations.addAll(decorativeAnnotations)
            }
        }
        if (frameEventList.isNotEmpty()) session.addEvents(frameEventList)
        return ActionResult(
            createLog("FILL SLOT for target : ${param.frame::class.qualifiedName}, slot : ${if (param.attribute == "this") "" else param.attribute}"),
            true
        )
    }
}

data class FillActionBySlot<T>(
    val generator: () -> T?,
    val frame: IFrame?,
    val slot: String?,
    val decorativeAnnotations: List<Annotation> = listOf()) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}, slot : ${slot}"),
            true
        )
        return FillAction(generator, wrapFiller.targetFiller, decorativeAnnotations).run(session)
    }
}

data class MarkFillerDone(val filler: AnnotatedWrapperFiller): StateAction {
    override fun run(session: UserSession): ActionResult {
        filler.markDone()
        return ActionResult(createLog("end filler for: ${filler.targetFiller.attribute}"))
    }
}

data class MarkFillerFilled(val filler: AnnotatedWrapperFiller): StateAction {
    override fun run(session: UserSession): ActionResult {
        filler.markFilled()
        return ActionResult(createLog("end filler for: ${filler.targetFiller.attribute}"))
    }
}

data class EndSlot(
    val frame: IFrame?, val slot: String?, val hard: Boolean) : StateAction {
    override fun run(session: UserSession): ActionResult {
        val wrapFiller = frame?.let { session.findWrapperFillerForTargetSlot(frame, slot) } ?: return ActionResult(
            createLog("cannot find filler for frame : ${if (frame != null) frame::class.qualifiedName else null}; slot: ${slot}"),
            true
        )
        return if (hard) MarkFillerDone(wrapFiller).run(session) else MarkFillerFilled(wrapFiller).run(session)
    }
}

class EndTopIntent : StateAction {
    override fun run(session: UserSession): ActionResult {
        val topFrameFiller = (session.schedule.firstOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>
        // find skills slot of main if there is one, we need a protocol to decide which intent to end
        if (topFrameFiller != null) {
            val currentSkill = (topFrameFiller.fillers["skills"]?.targetFiller as? MultiValueFiller<*>)?.findCurrentFiller()
            val currentIntent = ((currentSkill?.targetFiller as? InterfaceFiller<*>)?.vfiller?.targetFiller as? FrameFiller<*>)?.frame()
            if (currentSkill != null && currentIntent is IIntent) {
                return MarkFillerDone(currentSkill).run(session)
            }
        }
        if (topFrameFiller != null && topFrameFiller.frame() is IIntent) {
            return MarkFillerDone((session.schedule.first() as AnnotatedWrapperFiller)).run(session)
        }

        return ActionResult(null)
    }
}

abstract class AbstractAbortIntent(override var session: UserSession? = null) : IIntent {
    @JsonIgnore
    override val type = FrameKind.SMALLINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf("intentType" to listOf(RecoverOnly()))

    var intentType: InternalEntity? = null
    var intent: IIntent? = null

    abstract val builder: (String) -> InternalEntity?

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: AbstractAbortIntent? = this@AbstractAbortIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<AbstractAbortIntent?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(EntityFiller({tp.get()!!::intentType}, { s: String? -> intentType?.origValue = s}) { s -> builder(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> AbortIntentAction(this)
        }
    }

    open val defaultFailPrompt: (() -> ComponentDialogAct)? = null
    open val defaultSuccessPrompt: (() -> ComponentDialogAct)? = null
    open val defaultFallbackPrompt: (() -> ComponentDialogAct)? = null
    open val customizedSuccessPrompt: Map<String, () -> ComponentDialogAct> = mapOf()
}

data class AbortIntentAction(val frame: AbstractAbortIntent) : ChartAction {
    override fun run(session: UserSession): ActionResult {
        val specifiedQualifiedIntentName = frame.intentType?.value
        var targetFiller: AnnotatedWrapperFiller? = null
        val fillersNeedToPop = mutableSetOf<IFiller>()
        val prompts: MutableList<ComponentDialogAct> = mutableListOf()
        val mainScheduler = session.mainSchedule
        for (f in mainScheduler.reversed()) {
            fillersNeedToPop.add(f)
            if (f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*> && f.targetFiller.frame() is IIntent && (specifiedQualifiedIntentName == null || specifiedQualifiedIntentName == f.targetFiller.qualifiedTypeStr())) {
                targetFiller = f
                break
            }
        }
        if (targetFiller != null) {
            // target intent found
            var abortedFiller: AnnotatedWrapperFiller? = null
            while (mainScheduler.isNotEmpty()) {
                val top = mainScheduler.peek()
                // we only abort child of Multi Value Filler or the root intent; aborting other intents is meaningless
                if (top !in fillersNeedToPop && top is MultiValueFiller<*> && top.abortCurrentChild()) {
                    break
                } else {
                    mainScheduler.pop()
                    if (top is AnnotatedWrapperFiller && top.targetFiller is FrameFiller<*> && top.targetFiller.frame() is IIntent) {
                        abortedFiller = top
                    }
                }
            }

            while (session.schedulers.size > 1) {
                session.schedulers.removeLast()
            }

            val targetIntent = (targetFiller.targetFiller as FrameFiller<*>).frame() as IIntent
            val targetIntentName = with(session) {targetIntent.typeIdentifier()}
            val abortIntent = (abortedFiller!!.targetFiller as FrameFiller<*>).frame() as IIntent
            val abortIntentName = with(session) {abortIntent.typeIdentifier()}
            frame.intent = abortIntent
            if (frame.customizedSuccessPrompt.containsKey(abortIntentName)) {
                prompts.add(frame.customizedSuccessPrompt[abortIntentName]!!())
            } else {
                if (frame.intentType == null || frame.intentType!!.value == abortIntentName) {
                    frame.defaultSuccessPrompt?.let {
                        prompts.add(it())
                    }
                } else {
                    if (frame.defaultFallbackPrompt != null) {
                        prompts.add(frame.defaultFallbackPrompt!!())
                    } else if (frame.defaultSuccessPrompt != null) {
                        prompts.add(frame.defaultSuccessPrompt!!())
                    }
                }
            }
        } else {
            frame.defaultFailPrompt?.let {
                prompts.add(it())
            }
        }
        return ActionResult(
            prompts,
            createLog(prompts.map { it.templates.pick().invoke() }.joinToString { it }), true)
    }
}

data class Confirmation(
    override var session: UserSession? = null,
    val target: IFrame?,
    val slot: String,
    val prompts: () -> ComponentDialogAct, val implicit: Boolean = false, val actions: List<Action>? = null): IIntent {
    override val type = FrameKind.BIGINTENT
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "status" to listOf(SlotPromptAnnotation(listOf(TextOutputAction(prompts))), ConditionalAsk(LazyEvalCondition { !implicit }))
    )

    var status: io.opencui.core.confirmation.IStatus? = null

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: Confirmation? = this@Confirmation
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<Confirmation?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(
                    InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        implicit -> TextOutputAction(prompts)
        status is io.opencui.core.confirmation.No && (target != null || actions != null) -> {
            if (actions != null) {
                SeqAction(actions)
            } else {
                val path = session!!.findActiveFillerPathByFrame(target!!)
                val targetFiller = (if (slot.isNullOrEmpty() || slot == "this") path.lastOrNull() else ((path.lastOrNull() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>)?.fillers?.get(slot)) as? AnnotatedWrapperFiller
                if (targetFiller != null) {
                    SeqAction(
                        CleanupAction(listOf(targetFiller)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    null
                }
            }
        }
        else -> null
    }
}

data class FreeActionConfirmation(
    override var session: UserSession? = null,
    val confirmPrompts: () -> ComponentDialogAct,
    val actionPrompts: () -> ComponentDialogAct,
    val implicit: Boolean = false): IIntent {
    override val type = FrameKind.BIGINTENT

    var status: io.opencui.core.confirmation.IStatus? = null
    var action: IIntent? = null

    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "status" to listOf(SlotPromptAnnotation(listOf(TextOutputAction(confirmPrompts))), ConditionalAsk(LazyEvalCondition { !implicit })),
        "action" to listOf(SlotPromptAnnotation(listOf(TextOutputAction(actionPrompts))), ConditionalAsk(LazyEvalCondition { status is io.opencui.core.confirmation.No && !implicit }))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: FreeActionConfirmation? = this@FreeActionConfirmation
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<FreeActionConfirmation?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(InterfaceFiller({ tp.get()!!::status }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            filler.addWithPath(InterfaceFiller({ tp.get()!!::action }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.IIntent::class.qualifiedName!!)))
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        implicit -> TextOutputAction(confirmPrompts)
        else -> null
    }
}

data class ValueCheck(
    override var session: UserSession? = null,
    val conditionActionPairs: List<Pair<()->Boolean, List<Action>>>): IIntent {
    constructor(session: UserSession?, checker: () -> Boolean, actions: List<Action>): this(session, listOf(Pair(checker, actions)))

    override val type = FrameKind.BIGINTENT
    override var annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: ValueCheck? = this@ValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<ValueCheck?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        var action: Action? = null
        for (p in conditionActionPairs) {
            if (!p.first()) {
                action = SeqAction(p.second)
                break
            }
        }
        return action
    }
}

data class OldValueCheck(
    override var session: UserSession? = null,
    val checker: () -> Boolean,
    val toBeCleaned: List<Pair<IFrame?, String?>>,
    val prompts: () -> ComponentDialogAct
): IIntent {
    override val type = FrameKind.BIGINTENT
    override var annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: OldValueCheck? = this@OldValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<OldValueCheck?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        !checker() -> {
            val targetFillers = mutableListOf<AnnotatedWrapperFiller>()
            var refocusPath: LinkedList<IFiller> = LinkedList()
            for (clean in toBeCleaned) {
                val frame = clean.first!!
                val slot = clean.second
                val path = session!!.findActiveFillerPathForTargetSlot(frame, slot)
                val targetFiller = path.lastOrNull() as? AnnotatedWrapperFiller
                if (targetFiller != null) {
                    targetFillers += targetFiller
                    // calculate first slot in natural order to which we refocus
                    val step = min(refocusPath.size, path.size)
                    var index = 0
                    while (index < step) {
                        if (refocusPath[index] != path[index]) break
                        index++
                    }
                    if (index == 0 || index == step) {
                        if (path.size > refocusPath.size) {
                            refocusPath = path
                        }
                    } else {
                        val frameFiller = refocusPath[index-1] as? FrameFiller<*>
                        if (frameFiller != null) {
                            val ia = frameFiller.fillers.values.indexOf(refocusPath[index])
                            val ib = frameFiller.fillers.values.indexOf(path[index])
                            if (ib < ia) refocusPath = path
                        }
                    }
                }
            }
            if (targetFillers.isNotEmpty() && refocusPath.isNotEmpty()) {
                SeqAction(
                    TextOutputAction(prompts),
                    CleanupAction(targetFillers),
                    RefocusAction(refocusPath as List<ICompositeFiller>)
                )
            } else {
                null
            }
        }
        else -> null
    }
}

data class MaxDiscardAction(
    val targetSlot: MutableList<*>, val maxEntry: Int
) : SchemaAction {
    override fun run(session: UserSession): ActionResult {
        val size = targetSlot.size
        if (size > maxEntry) {
            targetSlot.removeAll(targetSlot.subList(maxEntry, targetSlot.size))
        }
        return ActionResult(createLog("DISCARD mv entries that exceed max number, from $size entries to $maxEntry entries"), true)
    }
}


data class MaxValueCheck(
    override var session: UserSession? = null,
    val targetSlotGetter: () -> MutableList<*>?,
    val maxEntry: Int,
    val prompts: () -> ComponentDialogAct
): IIntent {
    override val type = FrameKind.BIGINTENT
    override var annotations: Map<String, List<Annotation>> = mapOf()

    val targetSlot: MutableList<*>?
        get() {
            return targetSlotGetter()
        }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: MaxValueCheck? = this@MaxValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<MaxValueCheck?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            targetSlot != null && targetSlot!!.size > maxEntry -> SeqAction(
                TextOutputAction(prompts),
                MaxDiscardAction(targetSlot!!, maxEntry)
            )
            else -> null
        }
    }
}


data class Ordinal(
    @get:JsonIgnore var value: String
): Serializable {
    var origValue: String? = null
    @JsonValue
    override fun toString(): String = value

    fun name(): String {
        val v = value.toInt()
        val remByHundred = v % 100
        if (remByHundred == 11 || remByHundred == 12 || remByHundred == 13) return "${v}th"
        return when (v%10) {
            1 -> "${v}st"
            2 -> "${v}nd"
            3 -> "${v}rd"
            else -> "${v}th"
        }
    }
}

data class NextPage(override var session: UserSession? = null) : IFrame {
    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: NextPage? = this@NextPage
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<NextPage?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class PreviousPage(override var session: UserSession? = null) : IFrame {
    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: PreviousPage? = this@PreviousPage
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<PreviousPage?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class FilterCandidate(override var session: UserSession? = null) : IFrame {
    @JsonIgnore
    override val type = FrameKind.FRAME

    var conditionMapJson: String? = null

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf("conditionMapJson" to listOf(RecoverOnly()))

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: FilterCandidate? = this@FilterCandidate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<FilterCandidate?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(EntityFiller({tp.get()!!::conditionMapJson}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

abstract class ValueRecSourceWrapper(override var session: UserSession? = null) : IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: ValueRecSourceWrapper? = this@ValueRecSourceWrapper
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<ValueRecSourceWrapper?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class BadCandidate<T>(override var session: UserSession? = null, var value: T?) : IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: BadCandidate<T>? = this@BadCandidate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<BadCandidate<T>?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}


data class BadIndex(override var session: UserSession? = null, var index: Int) : IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: BadIndex? = this@BadIndex
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<BadIndex?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }
}

data class PagedSelectable<T: Any> (
    override var session: UserSession? = null,
    var suggestionIntentBuilder: FullFrameBuilder?,
    val kClass: () -> KClass<T>,
    var promptTemplate: (List<T>) -> ComponentDialogAct,
    var pageSize: Int = 5,
    var target: IFrame? = null,
    var slot: String? = null,
    var hard: Boolean = false,
    @JsonIgnore var zeroEntryActions: List<Action> = listOf(),
    @JsonIgnore var valueOutlierPrompt: ((BadCandidate<T>) -> ComponentDialogAct)? = null,
    @JsonIgnore var indexOutlierPrompt: ((BadIndex) -> ComponentDialogAct)? = null,
    @JsonIgnore var singleEntryPrompt: ((T) -> ComponentDialogAct)? = null,
    @JsonIgnore var implicit: Boolean = false,
    @JsonIgnore var autoFillSwitch: () -> Boolean = {true},
    @JsonIgnore var candidateListProvider: (() -> List<T>)? = null,
): IIntent {
    // So that we can use the old construction.
    constructor(
        session: UserSession? = null,
        valuesProvider: (() -> List<T>)? = null,
        kClass: () -> KClass<T>,
        promptTemplate: (List<T>) -> ComponentDialogAct,
        pageSize: Int = 5,
        target: IFrame? = null,
        slot: String? = null,
        hard: Boolean = false,
        zeroEntryActions: List<Action> = listOf(),
        valueOutlierPrompt: ((BadCandidate<T>) -> ComponentDialogAct)? = null,
        indexOutlierPrompt: ((BadIndex) -> ComponentDialogAct)? = null,
        singleEntryPrompt: ((T) -> ComponentDialogAct)? = null,
        implicit: Boolean = false,
        autoFillSwitch: () -> Boolean = {true}
    ) : this (session, null, kClass, promptTemplate, pageSize, target, slot, hard, zeroEntryActions, valueOutlierPrompt, indexOutlierPrompt, singleEntryPrompt, implicit, autoFillSwitch, valuesProvider)

    init {
        // make one of them are true
        assert((candidateListProvider == null).xor(suggestionIntentBuilder == null))
    }

    val sepConfirm by lazy {
        singleEntryPrompt?.let {
            Confirmation(
                    session, this, "index",
                    {it(pick()!!)},
                    implicit, actions = zeroEntryActions.filter { it !is TextOutputAction })
        }
    }

    var suggestionIntent: IIntent? = suggestionIntentBuilder?.invoke(session!!) as IIntent?

    val candidates: List<T>
        get() {
            return if (suggestionIntentBuilder != null) {
                val providedValues = getPropertyValueByReflection(suggestionIntent!!, "result") as? List<T> ?: listOf()
                providedValues.filter(matcher)
            } else {
                candidateListProvider!!().filter(matcher)
            }
        }

    val lastPage: Int
        get() = candidates.size / pageSize - if (candidates.size % pageSize == 0) 1 else 0

    var page: Int? = null

    var index: Ordinal? = null

    val payload: List<T>
        get() {
            val p = if (page == null) 0 else page!!
            return candidates.subList(p * pageSize, min((p + 1) * pageSize, candidates.size)).toList()
        }

    var conditionMap: ObjectNode? = null
    private val matcher: (T) -> Boolean = { t: T ->
        if (conditionMap == null) {
            true
        } else {
            if (t is IFrame) {
                var res = true
                for (entry in conditionMap!!.fields()) {
                    val target: String? = if (entry.key == "@class") {
                        Json.encodeToString(t!!::class.qualifiedName!!)
                    } else {
                        val memberCallable = t!!::class.members.firstOrNull { it.name == entry.key }
                        memberCallable?.call(t)?.let {
                            Json.encodeToString(it)
                        }
                    }
                    if (!entry.value.isArray) {
                        res = false
                        break
                    }
                    res = res && (entry.value as ArrayNode).firstOrNull { target == it.toString() } != null
                    if (!res) break
                }
                res
            } else {
                if (conditionMap!!.size() >= 2) {
                    var values: ArrayNode? = null
                    for (f in conditionMap!!.fieldNames()) {
                        if (f == "@class") continue
                        values = conditionMap!!.get(f) as? ArrayNode
                        break
                    }
                    values?.firstOrNull { Json.encodeToString(t as Any) == it.toString() } != null
                } else {
                    true
                }
            }
        }
    }

    fun nextPage(): Int {
        var p = if (page == null) 1 else page!! + 1
        if (p > lastPage) p = 0
        return p
    }

    fun prevPage(): Int {
        var p = if (page == null) -1 else page!! - 1
        if (p < 0) p = lastPage
        return p
    }

    fun select(conditionMapJson: String): String {
        val condition = if (conditionMap == null) {
            ObjectNode(JsonNodeFactory.instance)
        } else {
            conditionMap!!.deepCopy()
        }
        val additionalConditionMap = Json.parseToJsonElement(conditionMapJson)
        if (additionalConditionMap.isObject) {
            for (entry in additionalConditionMap.fields()) {
                if (!entry.value.isArray) continue
                if ((entry.value as ArrayNode).size() == 0) {
                    condition!!.remove(entry.key)
                    continue
                }
                if (condition!!.has(entry.key)) {
                    condition.replace(entry.key, entry.value)
                } else {
                    condition.set(entry.key, entry.value)
                }
            }
        }
        return condition.toString()
    }

    fun isConditionEmpty(): Boolean {
        return conditionMap == null || conditionMap!!.isEmpty
    }

    fun pickWithIndex(index: Int): T? {
        return payload[index - 1]
    }

    fun pick(): T? = pickWithIndex(index!!.value.toInt())

    fun constructUserChoice(): ObjectNode? {
        val obj = ObjectNode(JsonNodeFactory.instance)
        return if (!isConditionEmpty()) {
            for (entry in conditionMap!!.fields()) {
                val arr = entry.value as? ArrayNode
                if (arr != null && arr.size() == 1) {
                    obj.replace(entry.key, arr[0])
                }
            }
            obj
        } else {
            null
        }
    }

    fun generateCandidate(): Any? {
        return if (candidates.isEmpty()) {
            constructUserChoice()
        } else {
            pick()
        }
    }

    fun singleEntryAutoFill(): Boolean {
        return candidates.size == 1 && isConditionEmpty() && autoFillSwitch() && hard
    }

    fun shrinkToSingleEntry(): Boolean {
        return candidates.size == 1 && !isConditionEmpty()
    }

    fun outlierValue(): Boolean {
        return candidates.isEmpty() && !isConditionEmpty() && hard
    }

    fun generateAutoFillIndex(): Ordinal? {
        return if (singleEntryAutoFill() || shrinkToSingleEntry()) Ordinal("1")
                else if (outlierValue()) Ordinal("-1")
                else null
    }

    fun isIndexValid(): Boolean {
        val size = payload.size
        val indexValue = index?.value?.toIntOrNull()
        return indexValue != null && indexValue >= 1 && indexValue <= size
    }

    @JsonIgnore
    override val type = FrameKind.SMALLINTENT

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        if (target == null) return null
        return session!!.findWrapperFillerForTargetSlot(target!!, slot)
    }

    @JsonIgnore
    val _check_index = ValueCheck(session, {isIndexValid()}, listOf(LazyPickAction {
        if (outlierValue())
            SeqAction(
                TextOutputAction(convertDialogActGen({getBadCandidate()}, valueOutlierPrompt!!)),
                ReinitActionBySlot(listOf(Pair(this, "index"))),
                CleanupActionBySlot(listOf(Pair(this, "page"), Pair(this, "conditionMap"), Pair(this, "index"))))
        else SeqAction(
            TextOutputAction(convertDialogActGen({getBadIndex()}, indexOutlierPrompt!!)),
            ReinitActionBySlot(listOf(Pair(this, "index"))),
            CleanupActionBySlot(listOf(Pair(this, "index"))))
    }))

    fun getBadCandidate(): BadCandidate<T> {
        return BadCandidate(session, Json.getConverter(session, kClass().java).invoke(constructUserChoice()))
    }

    fun getBadIndex(): BadIndex {
        return BadIndex(session, index!!.value.toInt())
    }

    @JsonIgnore
    override val annotations = mapOf<String, List<Annotation>>(
        "index" to listOf<Annotation>(
            SlotPromptAnnotation(listOf(TextOutputAction(convertDialogActGen({payload}, promptTemplate)))),
            ConditionalAsk(LazyEvalCondition { candidates.isNotEmpty() || outlierValue() }),
            SlotInitAnnotation(FillActionBySlot({generateAutoFillIndex()},  this, "index")),
            ValueCheckAnnotation(_check_index),
            ConfirmationAnnotation { searchConfirmation("index") }
        ),
        "conditionMap" to listOf(NeverAsk()),
        "page" to listOf(NeverAsk())
    )

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "index" -> if (singleEntryAutoFill() && singleEntryPrompt != null) sepConfirm else null
            else -> null
        }
    }

    override fun searchStateUpdateByEvent(event: String): IFrameBuilder? {
        val nextPage = NextPage(session)
        val previousPage = PreviousPage(session)
        val filterCandidate = FilterCandidate(session)
        return when (event) {
            NEXTPAGE -> intentBuilder<NextPage>(
                nextPage,
                listOf(UpdateRule({ with(nextPage) { true } },
                    DirectlyFillActionBySlot({ with(nextPage) { nextPage() } }, this, "page")
                )
                )
            )
            PREVIOUSPAGE -> intentBuilder<PreviousPage>(
                previousPage,
                listOf(UpdateRule({ with(previousPage) { true } },
                    DirectlyFillActionBySlot({ with(previousPage) { prevPage() } }, this, "page")
                )
                )
            )
            FILTERCANDIDATE -> intentBuilder<FilterCandidate>(
                filterCandidate, listOf(UpdateRule({ with(filterCandidate) { conditionMapJson != null } },
                    SeqAction(
                        DirectlyFillActionBySlot(
                            { with(filterCandidate) { Json.decodeFromString<ObjectNode>(select(conditionMapJson!!)) } },
                            this,
                            "conditionMap"
                        ),
                        DirectlyFillActionBySlot({ with(filterCandidate) { 0 } }, this, "page"),
                        ReinitActionBySlot(listOf(Pair(this, "index"))),
                        CleanupActionBySlot(listOf(Pair(this, "index")))
                    )
                )
                )
            )
            else -> null
        }
    }

    override fun searchResponse(): Action? {
        val candidate = generateCandidate()
        return when {
            candidates.isEmpty() && isConditionEmpty() && hard ->
                SeqAction(zeroEntryActions)
            candidate != null ->
                SeqAction(
                    object : Action {
                        override fun run(session: UserSession): ActionResult {
                            return ActionResult(null, true)
                    }
                },
                FillAction({candidate}, findTargetFiller()!!.targetFiller, listOf<Annotation>())
            )
            else -> null
        }
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object: FillBuilder {
        var frame: PagedSelectable<T>? = this@PagedSelectable
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<PagedSelectable<T>?> ?: ::frame
            val filler = FrameFiller({ tp }, path)

            with(filler) {
                if (suggestionIntentBuilder != null) {
                    val suggestIntentFiller =
                        suggestionIntent!!.createBuilder(::suggestionIntent).invoke(path.join("suggestionIntent", suggestionIntent)) as FrameFiller<*>
                    add(suggestIntentFiller)
                    //customize suggestion intent filler
                    fillers["suggestionIntent"]!!.disableResponse()
                    suggestionIntentBuilder!!.init(session!!, suggestIntentFiller as FrameFiller<*>)
                }
                addWithPath(EntityFiller({tp.get()!!::page}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({tp.get()!!::conditionMap}) { s ->
                    val s1 = Json.decodeFromString<String>(s)
                    Json.decodeFromString(s1)
                })
                addWithPath(EntityFiller({tp.get()!!::index}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    companion object {
        val NEXTPAGE = io.opencui.core.NextPage::class.qualifiedName!!
        val PREVIOUSPAGE = io.opencui.core.PreviousPage::class.qualifiedName!!
        val FILTERCANDIDATE = io.opencui.core.FilterCandidate::class.qualifiedName!!
    }
}


abstract class AbstractValueClarification<T: Any>(
    override var session: UserSession? = null,
    open val getClass: () -> KClass<T>,
    open val source: List<T>,
    open var targetFrame: IFrame,
    open var slot: String): IIntent {
    override val type = FrameKind.BIGINTENT

    abstract var target: T?
    abstract fun _rec_target(it: T?): PagedSelectable<T>


    fun targetSlotAlias(): String {
        return session!!.chatbot!!.duMeta.getSlotMetas(targetFrame::class.qualifiedName!!).firstOrNull { it.label == slot }?.triggers?.firstOrNull() ?: ""
    }

    fun normalize(t: T): String {
        return session!!.chatbot!!.duMeta.getEntityInstances(t::class.qualifiedName!!)[t.toString()]?.firstOrNull() ?: t.toString()
    }

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        return session!!.findWrapperFillerForTargetSlot(targetFrame, slot)
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: AbstractValueClarification<T>? = this@AbstractValueClarification
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<AbstractValueClarification<T>?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            when {
                getClass().isSubclassOf(IFrame::class) -> {
                    val cFiller = (target as IFrame).createBuilder().invoke(path.join("target", target as IFrame)) as FrameFiller<*>
                    filler.add(cFiller)
                }
                getClass().isAbstract -> {
                    val cFiller = InterfaceFiller({ tp.get()!!::target }, createFrameGenerator(tp.get()!!.session!!, getClass().qualifiedName!!))
                    filler.addWithPath(cFiller)
                }
                else -> {
                    val cFiller = EntityFiller({ tp.get()!!::target }) { s -> Json.decodeFromString(s, getClass())}
                    filler.addWithPath(cFiller)
                }
            }
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        else -> FillAction({ target }, findTargetFiller()!!.targetFiller, listOf<Annotation>())
    }
}

data class SlotType(@get:JsonIgnore var value: String) : Serializable {
    @JsonIgnore var session: UserSession? = null
    @JsonValue
    override fun toString() : String = value
}

abstract class AbstractSlotUpdate<T: Any>(override var session: UserSession? = null): IIntent {
    override val type = FrameKind.SMALLINTENT

    var originalSlot: SlotType? = null
    var oldValue: T? = null
    var newValue: T? = null
    var index: Ordinal? = null // for multi-value slot update
    var originalValue: T? = null
    var confirm: io.opencui.core.confirmation.IStatus? = null

    fun buildT(s: String): T {
        val slotFiller = findOriginalSlotFiller()?.targetFiller
        val type = if (slotFiller is EntityFiller<*>) slotFiller.qualifiedTypeStr() else if (slotFiller is MultiValueFiller<*>) slotFiller.qualifiedTypeStrForSv() else null
        return (if (type != null) Json.decodeFromString(s, session!!.findKClass(type)!!) else Json.decodeFromString<String>(s)) as T
    }

    fun normalizedWrapper(t: Any?): String {
        if (t == null) return ""
        return session!!.chatbot!!.duMeta.getEntityInstances(t::class.qualifiedName!!)[t.toString()]?.firstOrNull() ?: t.toString()
    }

    fun originalValue(): T? {
        return originalValue
    }

    fun originalValueInit(): T? {
        val f = findTargetFiller()?.targetFiller as? TypedFiller<T>
        return f?.target?.get()
    }

    fun isMV(): Boolean {
        return findOriginalSlotFiller()?.targetFiller is MultiValueFiller<*>
    }

    fun findIndexCandidates(ordinal: Ordinal?): List<Ordinal> {
        if (ordinal != null) return listOf(ordinal)
        val res = mutableListOf<Ordinal>()
        val fillers = (findOriginalSlotFiller()!!.targetFiller as MultiValueFiller<*>).fillers
        for (iv in fillers.withIndex()) {
            if (iv.value.done() && (oldValue == null || oldValue == (iv.value.targetFiller as? EntityFiller<*>)?.target?.get())) {
                res += Ordinal((iv.index + 1).toString())
            }
        }
        return res
    }

    fun validateSlotIndex(): Boolean {
        return (findOriginalSlotFiller()!!.targetFiller as MultiValueFiller<*>).fillers.size >= index!!.value.toInt()
    }

    fun getValueByIndex(index: Ordinal): T? {
        val targetFiller = findOriginalSlotFiller()?.targetFiller
        if (targetFiller !is MultiValueFiller<*>) return null
        if (targetFiller.fillers.size < index.value.toInt()) return null
        return (targetFiller.fillers[index.value.toInt()-1].targetFiller as TypedFiller<T>).target.get()
    }

    fun findTargetFiller(): AnnotatedWrapperFiller? {
        val f = findOriginalSlotFiller()
        if (f?.targetFiller !is MultiValueFiller<*>) return f
        val mvf = f.targetFiller as MultiValueFiller<*>
        if (index == null || index!!.value.toInt() <= 0 || mvf.fillers.size < index!!.value.toInt()) return null
        return mvf.fillers.get(index!!.value.toInt() - 1)
    }

    fun findOriginalSlotFiller(): AnnotatedWrapperFiller? {
        if (originalSlot == null) return null
        val filter = { f: IFiller ->
            val param = f.path!!.path.last()
            val slotName = if (param.attribute == "this") param.frame::class.qualifiedName!! else "${param.frame::class.qualifiedName}.${param.attribute}"
            f is AnnotatedWrapperFiller && originalSlot?.value == slotName
        }
        var topFiller = session!!.mainSchedule.firstOrNull() as? AnnotatedWrapperFiller
        if (((topFiller?.targetFiller as? FrameFiller<*>)?.fillers?.get("skills")?.targetFiller as? MultiValueFiller<*>)?.findCurrentFiller() != null) {
            topFiller = ((topFiller.targetFiller as FrameFiller<*>).fillers["skills"]!!.targetFiller as MultiValueFiller<*>).findCurrentFiller()
        }
        val candidate = session!!.findFillerPath(topFiller, filter)
        return candidate.lastOrNull() as? AnnotatedWrapperFiller
    }

    fun needConfirm(): Boolean {
        return oldValue != null && originalValue() != null && oldValue.toString() != originalValue().toString()
    }

    // informNewValuePrompt and askNewValuePrompt are used in other frames, so they need to be locked before SlotUpdate ends
    abstract val informNewValuePrompt: () -> ComponentDialogAct
    abstract val askNewValuePrompt: () -> ComponentDialogAct
    abstract val oldValueDisagreePrompt: () -> ComponentDialogAct
    abstract val doNothingPrompt: () -> ComponentDialogAct
    abstract val askIndexPrompt: () -> ComponentDialogAct
    abstract val wrongIndexPrompt: () -> ComponentDialogAct
    abstract val indexRecPrompt: (List<Ordinal>) -> ComponentDialogAct

    fun genNewValueConfirmAnnotation(): ConfirmationAnnotation {
        // we need to lock the prompt here to avoid this SlotUpdate being cleared
        val informNewValueDialogAct = informNewValuePrompt()
        val confirmFrame = Confirmation(session, this, "newValue", {informNewValueDialogAct}, implicit = true)
        return ConfirmationAnnotation { confirmFrame }
    }
    fun genPromptAnnotation(): SlotPromptAnnotation {
        // we need to lock the prompt here to avoid this SlotUpdate being cleared
        val slotPromptDialogAct = askNewValuePrompt()
        return SlotPromptAnnotation(listOf(TextOutputAction({slotPromptDialogAct})))
    }

    val _check_index by lazy {
        OldValueCheck(session, {validateSlotIndex()}, listOf(Pair(this, "index")),
                wrongIndexPrompt)
    }

    val _rec_index = {it: Ordinal? ->
        PagedSelectable(session,
            {findIndexCandidates(it)},
            {Ordinal::class},
            indexRecPrompt,
            target = this, slot = "index", hard = true, zeroEntryActions = listOf(EndSlot(this, "index", true)))
    }

    override val annotations: Map<String, List<Annotation>> by lazy {
        mapOf(
            "originalSlot" to listOf(NeverAsk()),
            "oldValue" to listOf(NeverAsk()),
            "newValue" to listOf(NeverAsk()),
            "index" to listOf(
                ConditionalAsk(LazyEvalCondition {isMV()}),
                ValueCheckAnnotation(_check_index),
                TypedValueRecAnnotation<Ordinal>({_rec_index(this)}),
                SlotPromptAnnotation(listOf(TextOutputAction(askIndexPrompt)))),
            "originalValue" to listOf(NeverAsk(), SlotInitAnnotation(DirectlyFillActionBySlot({originalValueInit()},  this, "originalValue"))),
            "confirm" to listOf(
                ConditionalAsk(LazyEvalCondition { needConfirm() }),
                SlotPromptAnnotation(listOf(TextOutputAction(oldValueDisagreePrompt))))
        )
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: AbstractSlotUpdate<T>? = this@AbstractSlotUpdate
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<AbstractSlotUpdate<T>?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            val originalSlotFiller = EntityFiller({tp.get()!!::originalSlot}) { s -> Json.decodeFromString<SlotType>(s).apply { this.session = this@AbstractSlotUpdate.session } }
            filler.addWithPath(originalSlotFiller)
            val oFiller = EntityFiller({tp.get()!!::oldValue}) { s -> buildT(s)}
            filler.addWithPath(oFiller)
            val nFiller = EntityFiller({tp.get()!!::newValue}) { s -> buildT(s)}
            filler.addWithPath(nFiller)
            val iFiller = EntityFiller({tp.get()!!::index}) { s -> Json.decodeFromString(s)}
            filler.addWithPath(iFiller)
            val originalValueFiller = EntityFiller({tp.get()!!::originalValue}) { s -> buildT(s)}
            filler.addWithPath(originalValueFiller)
            filler.addWithPath(
                InterfaceFiller({ tp.get()!!::confirm }, createFrameGenerator(tp.get()!!.session!!, io.opencui.core.confirmation.IStatus::class.qualifiedName!!)))
            return filler
        }
    }

    override fun searchResponse(): Action? = when {
        confirm !is io.opencui.core.confirmation.No -> {
            val filler = findTargetFiller()
            if (filler == null) {
                TextOutputAction(doNothingPrompt)
            } else {
                var path: List<IFiller> = listOf()
                for (s in session!!.schedulers) {
                    path = session!!.findFillerPath(s.firstOrNull(), { it == filler })
                    if (path.isNotEmpty()) break
                }
                if (newValue == null) {
                    val promptAnnotation = genPromptAnnotation()
                    SeqAction(
                        CleanupAction(listOf(filler)),
                        UpdatePromptAction(filler, promptAnnotation),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                } else {
                    val newValueConfirmFrameAnnotation = genNewValueConfirmAnnotation()
                    SeqAction(
                        FillAction({ TextNode(newValue.toString()) },
                            filler.targetFiller,
                            listOf(newValueConfirmFrameAnnotation)),
                        CleanupAction(listOf(filler)),
                        RefocusAction(path as List<ICompositeFiller>)
                    )
                }
            }
        }
        else -> null
    }
}

data class UpdatePromptAction(
    val wrapperTarget: AnnotatedWrapperFiller?,
    val prompt: SlotPromptAnnotation): SchemaAction {
    override fun run(session: UserSession): ActionResult {
        wrapperTarget?.targetFiller?.decorativeAnnotations?.clear()
        wrapperTarget?.targetFiller?.decorativeAnnotations?.add(prompt)
        return ActionResult(createLog("UPDATED PROMPTS for filler ${wrapperTarget?.attribute}"), true)
    }
}

data class PhoneNumber(
    @get:JsonIgnore
    override var value: String
) : InternalEntity {
    override var origValue: String? = null

    @JsonValue
    override fun toString(): String = value

    companion object {
        @JsonIgnore
        val valueGood: ((String) -> Boolean)? = { true }
    }
}

class IntentClarification(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    override var session: UserSession? = null
) : IFrame{

    @JsonIgnore
    override val type: FrameKind = FrameKind.FRAME

    var utterance: String? = null

    var source: Array<IIntent>? = null

    var target: IIntent? = null

    override val annotations: Map<String, List<Annotation>> = emptyMap()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: IntentClarification? = this@IntentClarification
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<IntentClarification?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            return filler
        }
    }

}