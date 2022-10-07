package io.opencui.core

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.opencui.core.da.DumbDialogAct
import io.opencui.core.hasMore.No
import io.opencui.serialization.Json
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import java.io.Serializable
import kotlin.collections.LinkedHashMap
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.full.isSubclassOf

/**
 * Each filler is a statechart itself, but also a part of larger statechart.
 */

/**
 * To make context read writable, we need to make some assumptions: there is only one frame
 * in focus, which interfaces can be referred as context by small intents. And we can only
 * access it locally, or top frame of filling stack.
 *
 * The correct behavior depends on two things:
 * 1. the session manager need to use isCompatible to filter the frames so that only compatible
 *    frame is push to dm.
 * 2. code generation need to make sure the context slot can be filled, simply by
 *    val filler = session.getFiller(slotName)
 *    if (filler != null and filler.notDone()) {
 *      addFiller(filler)
 *    }
 */

/**
 * On filler, the follow configuration also holds, given events: Map<String, SlotEvent>
 * 1. done(events) == true
 * 2. done(events) == false, hasOpenSlot(events) == true  then {pick(events) != null)
 * 3. done(events) == false, hasOpenSlot(events) == false then {choose(events) != null)
 *
 * For slot, done(events) is simply decided by state.
 * For frame: the done(events) logic should be:
 *      if hasOpenSlot(events) == true: return false.
 *      else for each frame: if frame.state != DONE, or frame.done(events) == false, return false
 *      return true
 */

/**
 * This is used to create the path so that we can find the best annotation. Frame is the host
 * type and attribute is the child, the type claimed on attribute is the declared type.
 *
 * For interface, we add a param with empty string.
 */
data class Param(val frame: IFrame, val attribute: String): Serializable

data class ParamPath(val path: List<Param>): Serializable {

    constructor(frame: IFrame): this(listOf(Param(frame, "this")))

    fun join(a: String, nf: IFrame? = null): ParamPath {
        val last = path.last()
        val list = mutableListOf<Param>()
        list.addAll(path.subList(0, path.size-1))
        list.add(Param(last.frame, a))
        if (nf != null) list.add(Param(nf, "this"))
        return ParamPath(list)
    }

    fun root(): IFrame {
        return path[0].frame
    }

    fun findSlotInitPair(): Pair<IFrame, String>? {
        if (this.path.isEmpty()) {
            return null
        }

        for (i in path.indices) {
            val rpath = if (i != path.size-1) path.subList(i, path.size).filter { it.attribute != "this" }.joinToString(separator = ".") { it.attribute }
            else path.last().attribute
            val frame = path[i].frame
            val t = frame.find<SlotInitAnnotation>(rpath)
            if (t != null) {
                return Pair(frame, rpath)
            }
        }
        return null
    }

    inline fun <reified T : Annotation> findAll(): List<T> {
        val res = mutableListOf<T>()
        if (this.path.isEmpty()) {
            return res
        }

        for (i in path.indices) {
            val rpath = if (i != path.size-1) path.subList(i, path.size).filter { it.attribute != "this" }.joinToString(separator = ".") { it.attribute }
            else path.last().attribute
            val frame = path[i].frame
            val t: List<T> = frame.findAll<T>(rpath)
            res.addAll(t)
        }
        return res
    }

    inline fun <reified T : Annotation> find(): T? {
        if (this.path.isEmpty()) {
            return null
        }

        if (T::class == AskStrategy::class) {
            val paramPath = if (path.last().attribute == "this" && path.size > 1) path[path.size - 2] else path.last()
            val frame = paramPath.frame
            val attr = paramPath.attribute
            return frame.find(attr) ?: AlwaysAsk() as T
        }

        for (i in path.indices) {
            val rpath = if (i != path.size-1) path.subList(i, path.size).filter { it.attribute != "this" }.joinToString(separator = ".") { it.attribute }
            else path.last().attribute
            val frame = path[i].frame
            val t: T? = patchFind(frame, rpath)
            if (t != null) return t
        }
        return null
    }

    // we need a patch since granularity of runtime annotations are finer than that of platform's
    inline fun <reified T : Annotation> patchFind(frame: IFrame, rpath: String): T? {
        val clazz = T::class
        when {
            clazz.isSubclassOf(PromptAnnotation::class) -> {
                var currentPath = rpath
                val origAnno: T? = frame.find(currentPath)
                if (origAnno != null) return origAnno
                if (currentPath.endsWith("._realtype")) {
                    currentPath = currentPath.substringBeforeLast("._realtype")
                    val interfaceSlotAnno: T? = frame.find(currentPath)
                    if (interfaceSlotAnno != null) return interfaceSlotAnno
                }
                if (currentPath.endsWith("._item")) {
                    currentPath = currentPath.substringBeforeLast("._item")
                    val mvSlotAnno: T? = frame.find(currentPath)
                    if (mvSlotAnno != null) return mvSlotAnno
                }
                return null
            }
            clazz == IValueRecAnnotation::class -> {
                val finalPath = if (rpath.endsWith("._hast.status._realtype")) {
                    rpath.substringBeforeLast("._hast.status._realtype")
                } else if (rpath.endsWith("._item")) {
                    rpath.substringBeforeLast("._item")
                } else {
                    rpath
                }
                return frame.find(finalPath)
            }
            else -> {
                return frame.find(rpath)
            }
        }
    }
}

/**
 * This is useful to carry out the default picking. There are two different designs that
 * we can follow: one is to use reflection, another is to use "compiler" technique where
 * we generate the all the things need so that we do need to reflect.
 *
 * How should we handle input events:
 * when we have some input, if it is compatible with existing focus, we should just try to
 * either consume it, or we should save for the later consumption. We can follow the simple
 * strategy for now, every time, we get FrameEvents from end user, we then also handle it
 * right then.
 *
 * User always input FrameEvents, and the frame in each FrameEvent is always set (It might
 * be filled via expectation).
 *
 * There are potentially two stages of input processing: first we decide whether we will need
 * it by current main intent, if we are, then we push it to one stack. If we are not, we push
 * it to another stack (or just throw it away?)
 *
 */
interface IFiller: Compatible, Serializable {
    var parent: ICompositeFiller?
    var path: ParamPath?
    val decorativeAnnotations: MutableList<Annotation>

    val attribute: String
        get() {
            if (path == null) return ""
            val last = path!!.path.last()
            return if (last.attribute != "this") {
                last.attribute
            } else {
                if (path!!.path.size == 1) last.frame::class.simpleName!! else path!!.path[path!!.path.size - 2].attribute
            }
        }

    // make scheduler state move on
    fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean = false

    fun onPop() {}

    fun onPush() {}

    // Is filler considered to be done given remaining events.
    // done means can neither move nor grow
    fun done(frameEvents: List<FrameEvent> = emptyList()): Boolean

    fun clear() {
        decorativeAnnotations.clear()
    }

    fun slotAskAnnotation(): PromptAnnotation? {
        val decorativePrompt = decorativeAnnotations.firstIsInstanceOrNull<PromptAnnotation>()
        if (decorativePrompt != null) return decorativePrompt
        return path?.find()
    }

    fun slotInformActionAnnotation(): SlotInformActionAnnotation? {
        return path?.find()
    }

    fun askStrategy(): AskStrategy {
        return path!!.find()!!
    }

    // fully type for compatible FrameEvent
    fun qualifiedEventType(): String? {
        return null
    }

    fun simpleEventType(): String? {
        var typeStr = qualifiedEventType() ?: return null
        val lastIndex = typeStr.lastIndexOf('.')
        if (lastIndex != -1) {
            typeStr = typeStr.substring(lastIndex + 1)
        }
        return typeStr
    }
}

// return true if the valid is considered good.
typealias ValueChecker = (String, String?) -> Boolean

// The goal of this to fill the slot from typed string form, to typed form.
abstract class AEntityFiller : IFiller, Committable {
    override var path: ParamPath? = null
    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    // it is important to keep FrameEvent in filler in order to tell whether it is autofilled
    var event: FrameEvent? = null

    var done: Boolean = false
    var valueGood: ValueChecker? = null
    var value: String? = null
    var origValue: String? = null

    override fun done(frameEvents: List<FrameEvent>): Boolean = done

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val frameEvent: FrameEvent? = flatEvents.firstOrNull { isCompatible(it) }
                ?: flatEvents.firstOrNull { !it.isUsed && !it.inferredFrom && it.turnId == session.turnId && (this as? Infer)?.infer(it) != null }
        if (frameEvent == null) {
            session.schedule.state = Scheduler.State.ASK
        } else {
            session.schedule.state = Scheduler.State.POST_ASK
        }
        return true
    }
}

interface TypedFiller<T> {
    val target: KMutableProperty0<T?>

    fun rawTypeStr(): String {
        val obj = target.get() ?: return target.returnType.toString()
        return obj::class.qualifiedName ?: target.returnType.toString()
    }

    fun qualifiedTypeStr(): String {
        val typeStr = rawTypeStr().let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return typeStr.substringBefore("<")
    }
}

interface BuildProperty<T> : Serializable {
    operator fun invoke() : KMutableProperty0<T?>
}

class EntityFiller<T>(
    val buildSink: () -> KMutableProperty0<T?>,
    val origSetter: ((String?) -> Unit)? = null,
    val builder: (String, String?) -> T?) : AEntityFiller(), TypedFiller<T> {
    constructor(buildSink: () -> KMutableProperty0<T?>,
                origSetter: ((String?) -> Unit)? = null,
                builder: (String) -> T?): this(buildSink, origSetter, {s, _ -> builder(s)})

    override val target: KMutableProperty0<T?>
        get() = buildSink()

    init {
        valueGood = {
            s, t ->
            try {
                builder(s, t) != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override val attribute: String
        get() = if (super.attribute.endsWith("._item")) super.attribute.substringBeforeLast("._item") else super.attribute

    override fun clear() {
        value = null
        origValue = null
        event = null
        origSetter?.invoke(null)
        target.set(null)
        done = false
        super.clear()
    }

    override fun qualifiedEventType(): String {
        val frameType = path!!.path.last().frame::class.qualifiedName!!.let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return frameType.substringBefore("<")
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return simpleEventType() == frameEvent.type && frameEvent.activeSlots.any { it.attribute == attribute }
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        val related = frameEvent.slots.find { it.attribute == attribute && !it.isUsed }!!
        related.isUsed = true

        if (valueGood != null && !valueGood!!.invoke(related.value, related.type)) return false
        target.set(builder.invoke(related.value, related.type))
        value = related.value
        origValue = related.origValue
        event = frameEvent
        decorativeAnnotations.clear()
        decorativeAnnotations.addAll(related.decorativeAnnotations)
        origSetter?.invoke(origValue)
        done = true
        return true
    }
}

class RealtypeFiller(override val target: KMutableProperty0<String?>, val inferFun: ((FrameEvent) -> FrameEvent?)? = null, val checker: (String) -> Boolean, val callback: () -> Unit): AEntityFiller(), TypedFiller<String>, Infer {

    init {
        valueGood = {
            s, _ ->
            try {
                checker(s)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        frameEvent.typeUsed = true

        val type = if (frameEvent.packageName.isNullOrEmpty()) frameEvent.type else "${frameEvent.packageName}.${frameEvent.type}"

        if (valueGood != null && !valueGood!!.invoke(type, null)) return false
        target.set(type)
        value = type
        origValue = type
        event = frameEvent
        done = true
        callback()
        return true
    }

    override fun infer(frameEvent: FrameEvent): FrameEvent? {
        return inferFun?.invoke(frameEvent)
    }

    override fun clear() {
        value = null
        origValue = null
        target.set(null)
        done = false
        super.clear()
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        val type = if (frameEvent.packageName.isNullOrEmpty()) frameEvent.type else "${frameEvent.packageName}.${frameEvent.type}"
        return !frameEvent.isUsed && checker(type)
    }
}

/**
 * The filling site is used to host the actual filling on slot. We maintain a stack of it in
 * schedule, the schedule is considered to be ready if the top of stack is open: or is not closed
 * and also have at least one open slot filler need to be filled.
 *
 * It is active when we pop its open slot filler to focus, and start to fill it. This is done by
 * first check whether there are material that we can already consume, and if we can, we will pick
 * the slot that has proposed filling already.
 *
 * When ready, there are three different cases:
 * 1. there is not proposed fillings, we just start to pick the first slot that is NOT close for filling.
 * 2. there is proposed fillings for this site, we just pick these instead (what happens if it is filled,
 *    we should do something based on the annotation).
 * 3. after we consume all the local fillings, we will check whether there are out of context fillings
 *    that is annotated as branch, if so, we will try to change schedule so that we can fill these
 *    branching slot.
 *
 * note: for the no branch and no local things, we just let it stay there, waiting to be consumed.
 */
interface ICompositeFiller : IFiller {
    // if the top filler is unable to move, we grow scheduler
    fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean = false
}

/**
 * This interface provide the support for finding the right filler based on path. The
 * filler it returns is the payload carrying filler, not Interface/Multi(Slot/Frame)
 * that is used as syntactical sugar.
 */
interface MappedFiller {
    fun get(s: String): IFiller?

    fun frame(): IFrame
}

interface Compatible {
    // decide whether frameEvent can be consumed by the filler
    fun isCompatible(frameEvent: FrameEvent): Boolean
}

interface Infer {
    // infer value from FrameEvent other than those which can be consumed directly
    fun infer(frameEvent: FrameEvent): FrameEvent?
}

interface Committable {
    // commit is responsible for mark the FrameEvent that it used
    fun commit(frameEvent: FrameEvent): Boolean
}

/**
 * Only one instance per user session are created for  implementation of this interface.
 */
interface ISingleton : IFrame {
    var filler: FrameFiller<*>
}

class AnnotatedWrapperFiller(val targetFiller: IFiller, val isSlot: Boolean = true): ICompositeFiller {
    val boolGatePackage = io.opencui.core.booleanGate.IStatus::class.java.`package`.name
    val hasMorePackage = io.opencui.core.hasMore.IStatus::class.java.`package`.name
    override var parent: ICompositeFiller? = null

    override var path: ParamPath? = targetFiller.path

    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    override fun slotAskAnnotation(): PromptAnnotation? {
        return targetFiller.slotAskAnnotation()
    }

    private fun infer(frameEvent: FrameEvent): FrameEvent? {
        return if (targetFiller.isCompatible(frameEvent)) FrameEvent("Yes", packageName = boolGatePackage ) else null
    }

    fun disableResponse() {
        needResponse = false
    }

    fun enableResponse() {
        needResponse = true
    }

    fun directlyFill(a: Any) {
        (targetFiller as TypedFiller<in Any>).target.set(a)
        markDone()
    }

    fun markFilled() {
        markedFilled = true
    }

    fun markDone() {
        markedDone = true
    }

    fun recheck() {
        checkFiller = null
    }

    fun reinit() {
        stateUpdateFiller?.clear()
    }

    val boolGate: BoolGate? by lazy {
        val askStrategy = askStrategy()
        if (askStrategy is BoolGateAsk) {
            BoolGate(path!!.root().session, askStrategy.generator, ::infer)
        } else {
            null
        }
    }

    val boolGateFiller: AnnotatedWrapperFiller? by lazy {
        (boolGate?.createBuilder()?.invoke(path!!.join("$attribute._gate", boolGate)) as? FrameFiller<*>)?.let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    fun initRecommendationFiller(session: UserSession, frameEvent: FrameEvent?): AnnotatedWrapperFiller? {
        val node = if (frameEvent != null) {
            if (targetFiller is EntityFiller<*>) {
                val slot = frameEvent.slots.first { it.attribute == targetFiller.attribute }
                slot.isUsed = true
                val value = Json.parseToJsonElement(slot.value)
                val type = slot.type!!
                Json.decodeFromJsonElement(value, session.findKClass(type)!!)
            } else {
                val obj = ObjectNode(JsonNodeFactory.instance)
                val type = frameEvent.fullType
                obj.replace("@class", TextNode(type))
                frameEvent.typeUsed = true
                for (slot in frameEvent.slots) {
                    slot.isUsed = true
                    obj.replace(slot.attribute, Json.parseToJsonElement(slot.value))
                }
                Json.decodeFromJsonElement(obj, session.findKClass(type)!!)
            }
        } else {
            null
        }
        val annotation = path?.find<IValueRecAnnotation>() ?: return null
        val recFrame = when (annotation) {
            is ValueRecAnnotation -> {
                annotation.recFrameGen()
            }
            is TypedValueRecAnnotation<*> -> {
                (annotation.recFrameGen as Any?.() -> IFrame).invoke(node)
            }
            else -> {
                throw Exception("IValueRecAnnotation type not supported")
            }
        }
        return (recFrame.createBuilder().invoke(path!!.join("$attribute._rec", recFrame)) as FrameFiller<*>).let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    var recommendationFiller: AnnotatedWrapperFiller? = null

    val stateUpdateFiller: AnnotatedWrapperFiller? by lazy {
        val slotInitAnnotation = path!!.find<SlotInitAnnotation>() ?: return@lazy null
        val updateIntent = ActionWrapperIntent(path!!.root().session, slotInitAnnotation.action)
        (updateIntent.createBuilder().invoke(path!!.join("$attribute._update", updateIntent)) as? FrameFiller<*>)?.let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    val stateUpdateDone: Boolean
        get() {
            return stateUpdateFiller?.done() != false
        }

    val recommendationDone: Boolean
        get() {
            // we save some effort in annotations; use "mvSlot" as key to ValueRecAnnotation instead of mvSlot._hast and mvSlot._item
            // if we encounter path that ends with ._hast or ._item we omit those suffix to find ValueRecAnnotation,
            // so we have to disable vr for mvSlot for now
            // maybe we will need vr for mvSlot and mvSlot._hast and mvSlot._item respectively in the future
            return targetFiller is MultiValueFiller<*> || path?.find<IValueRecAnnotation>() == null || recommendationFiller?.done() == true
        }

    fun initCheckFiller(): AnnotatedWrapperFiller? {
        val checkFrame = path!!.find<ValueCheckAnnotation>()?.checkFrame ?: return null
        return (checkFrame.createBuilder().invoke(path!!.join("$attribute._check", checkFrame)) as FrameFiller<*>).let {
            val res = AnnotatedWrapperFiller(it, false)
            res.parent = this@AnnotatedWrapperFiller
            it.parent = res
            res
        }
    }

    var confirmationFillers: List<AnnotatedWrapperFiller> = listOf()
    var checkFiller: AnnotatedWrapperFiller? = null

    val confirmDone: Boolean
        get() {
            val confirmFrame = path!!.find<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()
            val decorativeConfirm = targetFiller.decorativeAnnotations.firstIsInstanceOrNull<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()
            val confirmFrameList = mutableListOf<IFrame>()
            if (confirmFrame != null) confirmFrameList += confirmFrame
            if (decorativeConfirm != null) confirmFrameList += decorativeConfirm
            val currentFrames = confirmationFillers.mapNotNull { (it.targetFiller as? FrameFiller<*>)?.frame() }.toSet()
            return confirmFrameList.isEmpty() || (confirmFrameList.toSet() == currentFrames && confirmationFillers.map { it.done() }.fold(true) { acc, b -> acc && b })
        }

    val checkDone: Boolean
        get() {
            return path!!.find<ValueCheckAnnotation>() == null || checkFiller?.done() == true
        }

    var resultFiller: AnnotatedWrapperFiller? = (targetFiller as? FrameFiller<*>)?.fillers?.get("result")

    val resultDone: Boolean
        get() {
            return resultFiller?.done() != false
        }

    // response on/off switch
    var needResponse: Boolean = true
        get() = (targetFiller as? FrameFiller<*>)?.frame() is IIntent && field
        set(value) {
            field = value
        }

    var responseDone: Boolean = false

    var markedFilled: Boolean = false
    var markedDone: Boolean = false

    val ancestorTerminated: Boolean
        get() {
            var res = false
            var p: ICompositeFiller? = parent
            var c: ICompositeFiller = this
            while (p != null) {
                // check and confirm should be enabled if direct parent is marked filled
                if (p is AnnotatedWrapperFiller && (p.markedDone || (p.markedFilled && c != p.checkFiller && c !in p.confirmationFillers))) {
                    res = true
                    break
                }
                c = p
                p = p.parent
            }
            return res
        }

    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        if (boolGateFiller?.done(flatEvents) == false) {
            schedule.push(boolGateFiller!!)
            return true
        }
        val done = targetFiller.done(flatEvents)
        if (!done) {
            val frameEvent: FrameEvent? = flatEvents.firstOrNull { targetFiller.isCompatible(it) }
            if (!stateUpdateDone && frameEvent == null) {
                schedule.push(stateUpdateFiller!!)
                return true
            }
            // HasMore has a VR that does not handle HasMore FrameEvent; it's special here
            if (!recommendationDone
                && (frameEvent == null
                        || (targetFiller !is AEntityFiller && frameEvent.source == EventSource.USER && frameEvent.packageName != hasMorePackage)
                        || (targetFiller is AEntityFiller && frameEvent.slots.firstOrNull { !it.isLeaf } != null))) {
                if (recommendationFiller == null || frameEvent != null) {
                    recommendationFiller = initRecommendationFiller(session, frameEvent)
                }
                val slotPromptAnnotation = targetFiller.slotAskAnnotation()
                if (slotPromptAnnotation != null) {
                    recommendationFiller!!.decorativeAnnotations.add(slotPromptAnnotation)
                }
                schedule.push(recommendationFiller!!)
                return true
            }
            if (askStrategy() !is NeverAsk || frameEvent != null) {
                targetFiller.parent = this
                schedule.push(targetFiller)
                return true
            }
        } else {
            //value check
            if (!checkDone) {
                this.checkFiller = initCheckFiller()
                schedule.push(checkFiller!!)
                return true
            }

            // value confirm
            val confirmFrame = path?.find<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()
            val decorativeConfirm = targetFiller.decorativeAnnotations.firstIsInstanceOrNull<ConfirmationAnnotation>()?.confirmFrameGetter?.invoke()
            val confirmFrameList = mutableListOf<IFrame>()
            if (confirmFrame != null) confirmFrameList += confirmFrame
            if (decorativeConfirm != null) confirmFrameList += decorativeConfirm
            if (confirmFrameList.isEmpty()) {
                confirmationFillers = listOf()
            } else {
                if (confirmationFillers.isEmpty() || confirmationFillers.mapNotNull { (it.targetFiller as? FrameFiller<*>)?.frame() }.toSet() != confirmFrameList.toSet()) {
                    confirmationFillers = confirmFrameList.mapNotNull {
                        (it.createBuilder().invoke(path!!.join("$attribute._confirm", it)) as FrameFiller<*>).let {
                            val cf = AnnotatedWrapperFiller(it, false)
                            cf.parent = this@AnnotatedWrapperFiller
                            it.parent = cf
                            cf
                        }
                    }
                    schedule.push(confirmationFillers.first())
                    return true
                } else {
                    val firstNotDone = confirmationFillers.firstOrNull { !it.done() }
                    if (firstNotDone != null) {
                        schedule.push(firstNotDone)
                        return true
                    }
                }
            }

            // result value for FrameFiller
            if (!resultDone) {
                resultFiller!!.parent = this
                schedule.push(resultFiller!!)
                return true
            }
        }
        return false
    }

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        if (boolGateFiller?.done(flatEvents) == false) return false
        if (filled(session.activeEvents) && postFillDone() && needResponse && !responseDone) {
            session.schedule.state = Scheduler.State.RESPOND
            return true
        }
        return false
    }

    override fun onPush() {
        recommendationFiller?.responseDone = false
        (askStrategy() as? RecoverOnly)?.enable()
    }

    fun canEnter(frameEvents: List<FrameEvent>): Boolean {
        val askStrategyNotMet = (askStrategy() is ConditionalAsk && !askStrategy().canEnter())
                || (askStrategy() is NeverAsk && stateUpdateDone && frameEvents.firstOrNull { isCompatible(it) } == null)
                || (askStrategy() is RecoverOnly && stateUpdateDone && !askStrategy().canEnter() && frameEvents.firstOrNull { isCompatible(it) } == null)
                || (askStrategy() is BoolGateAsk && boolGate!!.status is io.opencui.core.booleanGate.No && frameEvents.firstOrNull { isCompatible(it) } == null)
        return !responseDone && !askStrategyNotMet && !ancestorTerminated
    }

    fun filled(frameEvents: List<FrameEvent>): Boolean {
        return markedFilled || targetFiller.done(frameEvents)
    }

    fun postFillDone(): Boolean {
        return checkDone && confirmDone && resultDone
    }

    override fun done(frameEvents: List<FrameEvent>): Boolean {
        return markedDone || !canEnter(frameEvents) || (filled(frameEvents) && postFillDone() && (!needResponse || responseDone))
    }

    override fun clear() {
        (askStrategy() as? RecoverOnly)?.disable()
        recommendationFiller?.clear()
        recommendationFiller = null
        boolGateFiller?.clear()
        for (confirmFiller in confirmationFillers) {
            confirmFiller.clear()
        }
        confirmationFillers = listOf()
        checkFiller = null
        targetFiller.clear()
        responseDone = false
        needResponse = true
        markedDone = false
        markedFilled = false
        super.clear()
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return targetFiller.isCompatible(frameEvent)
    }
}

/**
 * The invariance is following
 * if it is not done: if hasOpenSlot() is true, we pick works, or choose works. Either way,
 * we should make sure focus.parent == this.
 */
class FrameFiller<T: IFrame>(
    val buildSink: () -> KMutableProperty0<T?>,
    override var path: ParamPath?
) : ICompositeFiller, MappedFiller, TypedFiller<T>, Committable {

    override val target: KMutableProperty0<T?>
        get() = buildSink()

    override fun isCompatible(frameEvent: FrameEvent) : Boolean {
        return frameEvent.type == simpleEventType() && (frameEvent.activeSlots.isNotEmpty())
    }

    override fun qualifiedEventType(): String? {
        val frameType = path!!.path.last().frame::class.qualifiedName!!.let {
            if (it.endsWith("?")) it.dropLast(1) else it
        }
        return frameType.substringBefore("<")
    }

    override fun frame(): IFrame {
        return path!!.path.last().frame
    }

    override fun get(s: String): IFiller? {
        return fillers[s]
    }

    override fun commit(frameEvent: FrameEvent): Boolean {
        frameEvent.typeUsed = true
        committed = true
        return true
    }

    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()
    var fillers = LinkedHashMap<String, AnnotatedWrapperFiller>()
    var committed = false

    fun add(filler: IFiller) {
        val wrapper = AnnotatedWrapperFiller(filler)
        wrapper.parent = this
        filler.parent = wrapper
        fillers[filler.attribute] = wrapper
    }

    fun addWithPath(filler: IFiller) {
        // we have to set path first, it is the prerequisite of attribute, frame and many things else
        setChildPath(filler)
        add(filler)
    }

    fun setChildPath(filler: IFiller) {
        check(filler is TypedFiller<*>)
        filler.path = if (filler is FrameFiller<*>) path!!.join(filler.target.name, filler.target.get()) else path!!.join(filler.target.name)
    }


    /**
     * we find next filler in this order
     * 1. slot mentioned
     * 2. natural order
     */
    fun findNextFiller(frameEvents: List<FrameEvent>): AnnotatedWrapperFiller? {
        val childEntry = findNextChildFiller(frameEvents)
        return childEntry
    }

    fun findNextChildFiller(frameEvents: List<FrameEvent>): AnnotatedWrapperFiller? {
        return fillers.filterNot { it.key == "result" }.values.firstOrNull { !it.done(frameEvents) }
    }

    /**
     * Do static check first, then contextual check. If there are work to do, return false.
     */
    override fun done(frameEvents: List<FrameEvent>): Boolean {
        val a = findNextFiller(frameEvents)
        return a == null && (askStrategy() !is ExternalEventStrategy || committed)
    }

    override fun clear() {
        fillers.values.forEach {
            it.clear()
        }
        committed = false
        super.clear()
    }

    // Choose picks up the right frame to ask.
    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        val filler = findNextFiller(flatEvents) ?: return false
        schedule.push(filler)
        return true
    }

    override fun move(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        if (committed) return false
        if (askStrategy() is ExternalEventStrategy) {
            session.schedule.state = Scheduler.State.ASK
            return true
        }
        return false
    }
}

//
// Any one of the subtype will be useful there.
// for interface filler to work, we always need to ask what "implementation" will we work on next.
// There are two different ways of doing interfaces: inside frame
// THe trick we use to solve the prompt issue is:
// insert an "" attribute in the path for the interface, so we can always look for a.b.c
// in any frame.
//
class InterfaceFiller<T>(
    val buildSink: () -> KMutableProperty0<T?>,
    val factory: (String) -> IFrame?,
    val typeConverter: ((FrameEvent) -> FrameEvent?)? = null
) : ICompositeFiller, TypedFiller<T> {
    override val target: KMutableProperty0<T?>
        get() = buildSink()
    override fun isCompatible(frameEvent: FrameEvent) : Boolean {
        return askFiller.isCompatible(frameEvent)
    }

    override var parent: ICompositeFiller? = null
    override var path: ParamPath? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()


    var realtype: String? = null
    val askFiller: AnnotatedWrapperFiller by lazy {
        val entityFiller = RealtypeFiller(::realtype, inferFun = typeConverter, checker = { s -> factory.invoke(s) != null }) { buildVFiller() }
        entityFiller.path = this.path!!.join("$attribute._realtype")
        val af = AnnotatedWrapperFiller(entityFiller, false)
        af.parent = this
        entityFiller.parent = af
        af
    }
    var vfiller: AnnotatedWrapperFiller? = null

    override fun done(frameEvents: List<FrameEvent>): Boolean {
        return (vfiller != null && vfiller!!.done(frameEvents))
    }

    override fun clear() {
        askFiller.clear()
        vfiller?.clear()
        vfiller = null
        target.set(null)
        super.clear()
    }

    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        val schedule = session.schedule
        if (realtype == null) {
            schedule.push(askFiller)
            return true
        } else if (!askFiller.done(flatEvents)) {
            schedule.push(askFiller)
            return true
        }
        if (vfiller!!.done(flatEvents)) return false
        schedule.push(vfiller!!)
        return true
    }

    // If we already know the realtype (for whatever reason), we just make it ready for grow.
    private fun buildVFiller() {
        checkNotNull(realtype)
        val f = factory.invoke(realtype!!)!!
        val frameFiller = f.createBuilder().invoke(path!!.join("$attribute._realfiller", f)) as FrameFiller<*>
        // Make sure that we assign the empty value so any update will show
        // up for matching. for now, we do not support the casting in the condition.
        vfiller = AnnotatedWrapperFiller(frameFiller)
        vfiller!!.parent = this
        frameFiller.parent = vfiller
        // We do not have anything special annotation wise needed to included
        target.set(frameFiller.target.get() as T)
    }
}

class MultiValueFiller<T>(
    val buildSink: () -> KMutableProperty0<MutableList<T>?>,
    val buildFiller: (KMutableProperty0<T?>) -> IFiller
) : ICompositeFiller, TypedFiller<MutableList<T>> {
    val hasMorePackage = io.opencui.core.hasMore.IStatus::class.java.`package`.name
    override val target: KMutableProperty0<MutableList<T>?>
        get() = buildSink()

    override var path: ParamPath? = null
    override var parent: ICompositeFiller? = null
    override val decorativeAnnotations: MutableList<Annotation> = mutableListOf()

    private val minMaxAnnotation: MinMaxAnnotation? by lazy {
        path!!.find()
    }

    private val singleTargetFiller : IFiller by lazy { createTFiller(-1) }

    enum class SvType {
        ENTITY,
        FRAME,
        INTERFACE,
    }

    // need this wrapper to hold multiple properties of T
    inner class Wrapper(val index: Int) : Serializable {
        var tValue: T? = null
            set(value) {
                field = value
                if (value != null && index >= 0) {
                    val size = target.get()!!.size
                    if (index == size) {
                        target.get()!!.add(value)
                    } else {
                        target.get()!![index] = value
                    }
                }
            }
    }

    val svType: SvType
        get() = when (singleTargetFiller) {
            is AEntityFiller -> SvType.ENTITY
            is FrameFiller<*> -> SvType.FRAME
            is InterfaceFiller<*> -> SvType.INTERFACE
            else -> error("no such sv type")
        }

    private fun infer(frameEvent: FrameEvent): FrameEvent? {
        return if (singleTargetFiller.isCompatible(frameEvent)) {
            FrameEvent("Yes", packageName = hasMorePackage)
        } else {
            null
        }
    }

    override fun qualifiedEventType(): String? = singleTargetFiller.qualifiedEventType()

    fun qualifiedTypeStrForSv(): String? {
        return if (fillers.size > 0) (fillers[0].targetFiller as TypedFiller<*>).qualifiedTypeStr() else (singleTargetFiller as TypedFiller<*>).qualifiedTypeStr()
    }

    override fun isCompatible(frameEvent: FrameEvent): Boolean {
        return singleTargetFiller.isCompatible(frameEvent)
    }

    private val hasMoreAttribute = "_hast"
    var hasMore: HasMore? = null
    var hasMoreFiller: AnnotatedWrapperFiller? = null
    fun buildHasMore() {
        hasMore = HasMore(
            path!!.root().session, slotAskAnnotation()!!.actions, ::infer,
            if (minMaxAnnotation == null) {{true}} else {{target.get()!!.size >= minMaxAnnotation!!.min}},
            minMaxAnnotation?.minGen ?: { DumbDialogAct() })
        val hasMoreFrameFiller = (hasMore!!.createBuilder().invoke(path!!.join("$attribute.$hasMoreAttribute", hasMore)) as FrameFiller<*>)
        hasMoreFiller = AnnotatedWrapperFiller(hasMoreFrameFiller, false)
        hasMoreFrameFiller.parent = hasMoreFiller
        hasMoreFiller!!.parent = this
    }
    fun clearHasMore() {
        hasMoreFiller?.clear()
        hasMoreFiller = null
        hasMore = null
    }

    // We keep all the component filler so that we can change things around.
    val fillers = mutableListOf<AnnotatedWrapperFiller>()

    fun findCurrentFiller(): AnnotatedWrapperFiller? {
        return fillers.firstOrNull { !it.done() }
    }

    private fun createTFiller(index: Int): IFiller {
        val wrapper = Wrapper(index)
        return buildFiller(wrapper::tValue).apply {
            if (this !is FrameFiller<*>) {
                this.path = this@MultiValueFiller.path!!.join("${this@MultiValueFiller.attribute}._item")
            }
        }
    }

    private fun addFiller(filler: AnnotatedWrapperFiller) {
        fillers.add(filler)
    }

    fun abortCurrentChild(): Boolean {
        if (singleTargetFiller is AEntityFiller) return false
        val currentFiller = findCurrentFiller() ?: return false
        if (currentFiller == fillers.lastOrNull()) {
            fillers.removeLast()
            hasMoreFiller?.clear()
            hasMoreFiller = null
            hasMore = null
            return true
        }
        return false
    }

    override fun done(frameEvents: List<FrameEvent>): Boolean {
        return target.get() != null &&
                findCurrentFiller() == null &&
                (hasMore?.status is No || (target.get()!!.size >= minMaxAnnotation?.max ?: Int.MAX_VALUE && frameEvents.firstOrNull { isCompatible(it) } == null))
    }
    override fun clear() {
        hasMoreFiller?.clear()
        hasMoreFiller = null
        hasMore = null
        fillers.clear()
        target.get()?.clear()
        target.set(null)
        super.clear()
    }

    // we need to pick the right one and then remove it from
    override fun grow(session: UserSession, flatEvents: List<FrameEvent>): Boolean {
        if (target.get() == null) {
            target.set(mutableListOf())
        }
        val schedule = session.schedule
        check(hasMore?.status !is No)
        val currentFiller = findCurrentFiller()
        if (currentFiller != null) {
            schedule.push(currentFiller)
        } else if ((hasMoreFiller == null || !hasMoreFiller!!.done(flatEvents)) && flatEvents.firstOrNull { isCompatible(it) } == null) {
            if (hasMoreFiller == null) {
                buildHasMore()
            }
            schedule.push(hasMoreFiller!!)
        } else { //if something is mentioned but last filler is done, we assume it is for the next value
            clearHasMore()
            val ffiller = createTFiller(fillers.size)
            val wrapper = AnnotatedWrapperFiller(ffiller)
            ffiller.parent = wrapper
            wrapper.parent = this
            addFiller(wrapper)
            schedule.push(wrapper)
        }
        return true
    }
}
