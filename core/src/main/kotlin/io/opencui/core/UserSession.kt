package io.opencui.core

import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import io.opencui.serialization.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import io.opencui.core.user.IUserIdentifier
import io.opencui.core.user.UserIdentifier
import io.opencui.core.da.ComponentDialogAct
import io.opencui.core.da.FrameDialogAct
import io.opencui.core.da.SlotDialogAct
import io.opencui.sessionmanager.ChatbotLoader
import java.io.ObjectInputStream
import java.io.Serializable
import kotlin.reflect.KParameter

//
// Scheduler holds fillers in the single tree.
//
class Scheduler(val session: UserSession): ArrayList<IFiller>(), Serializable {
    // TODO(xiaobo): what is the difference between reschedule and recover, and where is the confirmation, vr, vc?
    enum class State {
        INIT,
        ASK,
        POST_ASK,
        RESCHEDULE,
        RESPOND,
        RECOVER,
    }

    var state: State = State.INIT

    fun push(item: IFiller) {
        add(item)
        item.onPush()
    }

    fun pop(): IFiller {
        val item = lastOrNull()
        if (!isEmpty()) {
            removeAt(size - 1)
        }
        item!!.onPop()
        return item
    }

    fun peek(): IFiller = last()

    /**
     * This is used when first expand the system. This call is guaranteed to work for
     * first time call on correct intent definition.
     */
    fun grow(): Boolean {
        var top = this.peek()
        while (!top.move(session, session.activeEvents)) {
            // find some open composite to put to top.
            val grown = if (top is ICompositeFiller) top.grow(session, session.activeEvents) else false
            if (!grown) return false
            top = this.peek()
        }
        return true
    }

    fun cleanup() {
        clear()
        state = State.INIT
    }

    fun toObjectNode(): ObjectNode {
        val objNode = ObjectNode(JsonNodeFactory.instance)
        objNode.replace("state", TextNode(state.name))
        val nodeArray = mutableListOf<JsonNode>()
        for (filler in this) {
            val node = ObjectNode(JsonNodeFactory.instance)
            node.replace("filler_type", TextNode(filler.javaClass.simpleName))
            node.replace("attribute", TextNode(filler.attribute))
            nodeArray.add(node)
        }
        objNode.replace("fillers", ArrayNode(JsonNodeFactory.instance, nodeArray))
        return objNode
    }
}

/**
 * The core of the interaction is a statechart where we have session level,
 * frame level (nested) and slot level decision need to make. And these statechart are
 * static in nature, but its behavior is dynamically decided by guard on the transition.
 *
 * It should be useful for UserSession to host the statechart at session level.
 *
 * At session level, we only need to do a couple of things:
 * a. when there is no active skill.
 *    1. where there is skill event.
 *    2. where there si no meaningful event.
 *    3. where there is slot events that can be fixed by constrained intent suggestions.
 * b. where there is active skill:
 *    1. where there is new skill event:
 *    2. where there is no new skill events:
 *       i. there is no event
 *       ii. there is slot event that is not compatiable.
 *       iii. there is slot event that is compatible.
 *
 */
interface StateChart {
    // This host all the events currently not fully consumed.
    val events : MutableList<FrameEvent>
    var turnId: Int

    // This ensures the parallelism of the StartChart at the top level.
    val schedulers: MutableList<Scheduler>

    val activeEvents: List<FrameEvent>
        get() = events.filter { !it.usedUp }

    val mainSchedule : Scheduler
        get() = schedulers.first()

    val schedule: Scheduler
        get() = schedulers.last()

    fun tryToRefocus(frameEvents: List<FrameEvent>): Pair<List<IFiller>, FrameEvent>?

    fun getActionByEvent(frameEvents: List<FrameEvent>): Action?

    val finishedIntentFiller: MutableList<AnnotatedWrapperFiller>
    
    fun addEvent(frameEvent: FrameEvent)
    fun addEvents(frameEvents: List<FrameEvent>)

    /**
     * Take one or more steps of statechart transition.
     * The main constraint is we can not have turnComplete in the middle of returned action list.
     *
     * 1. Based on current states of the chart forest.
     * 2. Take the most relevant FrameEvent.
     * 3. Build the action need to be executed at this state, which updates the state.
     *
     * There are three types of atomic actions:
     * Chart building, state transition, and message emission, of course, there are composite ones.
     */
    fun kernelStep(): List<Action>

}

data class Dedupper<T>(val max_size: Int) : LinkedList<T>(), Serializable {
    /**
     * Assume the caller will use it if it is new.
     */
    fun isNew(x: T) : Boolean {
        val contains = (x in this)
        return !contains
    }

    fun update(x: T) {
        val contains = (x in this)
        if (!contains) {
            this.addLast(x)
            if (this.size > max_size) this.removeFirst()
        }
    }
}


/**
 * UserSession is used to keep the history of the conversation with user. It will
 * only keep for certain amount of time.
 *
 * In the UserSession, we keep global value, and current intent, and filling schedule,
 * explanations left over, and background intent. Basically all the information needed
 * for continue the session.
 */
data class UserSession(
    val userIdentifier: IUserIdentifier,
    @Transient @JsonIgnore var chatbot: IChatbot? = null): LinkedHashMap<String, Any>(), Serializable, StateChart {

    // Default botInfo, need to be changed.
    var botInfo = BotInfo(chatbot!!.orgName, chatbot!!.agentName, chatbot!!.agentLang, chatbot!!.agentBranch)

    override val events = mutableListOf<FrameEvent>()

    // this is used for dedup the retried message from channels.
    val pastMessages = Dedupper<String>(9)

    override fun addEvent(frameEvent: FrameEvent) {
        frameEvent.updateTurnId(turnId)
        events.add(frameEvent)
    }

    override fun addEvents(frameEvents: List<FrameEvent>) {
        frameEvents.forEach {
            it.updateTurnId(turnId)
        }
        events.addAll(frameEvents)
    }

    /**
     * Chart building should not be exposed to execution.
     */
    fun userStep(): List<Action> {
        var res = kernelStep()
        while (res.size == 1 && (res[0] is KernelMode)) {
            res[0].run(this)
            res = kernelStep()
        }
        // make sure there is no chart building action leak to user space.
        assert(res.none { it is KernelMode })
        return res
    }

    // the timezone is session dependent. For example, when user ask about New York hotel, then ask the same
    // thing about san fransisco.
    var timezone : String? = null

    /**
     * We should always set this in the Dispatcher when create user session.
     */
    fun setUserIdentifier(pprofile: IUserIdentifier) {
        makeSingleton(USERIDENTIFIER)
        val userIdentifier = getGlobal<UserIdentifier>()
        userIdentifier!!.apply{channelType = pprofile.channelType; userId = pprofile.userId; channelLabel = pprofile.channelLabel }
    }

    var targetChannel: List<String> = listOf(SideEffect.RESTFUL)

    @JsonIgnore
    override val schedulers: MutableList<Scheduler> = mutableListOf(Scheduler(this))

    @JsonIgnore
    override var turnId: Int = 0

    @JsonIgnore
    var lastTurnRes: List<ActionResult> = listOf()

    override fun kernelStep(): List<Action> {
        // CUI logic is static in high order sense, we just hard code it.
        // system-driven process
        if (schedule.state == Scheduler.State.ASK) {
            return listOf(SlotAskAction())
        }

        if (schedule.state == Scheduler.State.POST_ASK) {
            val currentFiller = schedule.lastOrNull()
            if (currentFiller != null) {
                val events = activeEvents.sortedBy { if (it.refocused) 0 else 1 }
                val strictlyMatch = events.firstOrNull { currentFiller.isCompatible(it) }
                if (strictlyMatch != null) {
                    return listOf(SlotPostAskAction(currentFiller, strictlyMatch))
                }
                for (event in events.filter { it.turnId == turnId && !it.inferredFrom && !it.isUsed }) {
                    val inferredMatch = (currentFiller as? Infer)?.infer(event)
                    if (inferredMatch != null) {
                        event.inferredFrom = true
                        event.triggered = true
                        return listOf(SlotPostAskAction(currentFiller, inferredMatch))
                    }
                }
            }
        }

        if (schedule.state == Scheduler.State.RESPOND) {
            return listOf(RespondAction())
        }

        // user-driven process

        // state update
        val eventTriggeredTransition = getActionByEvent(activeEvents.filter { it.turnId == turnId && !it.triggered && !it.isUsed })
        if (eventTriggeredTransition != null) {
            return listOf(eventTriggeredTransition)
        }

        // special matcher for HasMore with PagedSelectable in it
        val refocusPair = tryToRefocus(activeEvents.filter { it.turnId == turnId && !it.refocused && !it.isUsed})
        // prevent from refocusing from kernel mode to user mode
        if (refocusPair != null && (!inKernelMode(schedule) || inKernelMode(refocusPair.first))) {
            val refocusFiller = refocusPair.first.last() as AnnotatedWrapperFiller
            return if (!refocusFiller.targetFiller.done() && refocusFiller.targetFiller is EntityFiller<*>) {
                listOf(SimpleFillAction(refocusFiller.targetFiller, refocusPair.second))
            } else {
                if ((refocusPair.first.last() as? AnnotatedWrapperFiller)?.targetFiller !is MultiValueFiller<*>) {
                    // view refocusing to multi-value slot as adding value, others as starting over
                    refocusPair.first.last().clear()
                }
                refocusPair.second.refocused = true
                listOf(RefocusAction(refocusPair.first))
            }
        }

        val frameEvent = activeEvents.filter { it.turnId == turnId && !it.triggered && !it.isUsed && !it.refocused && !it.inferredFrom }.firstOrNull()
        // new scheduler for new intent
        if (frameEvent != null) {
            val type = frameEvent.type
            if (type == "" && schedule.isEmpty()) {
                val fullyQualifiedName: String = SystemAnnotationType.IntentSuggestion.typeName
                if (!fullyQualifiedName.isEmpty()) {
                    if (fullyQualifiedName.lastIndexOf(".") >= 0 ) {
                        return listOf(StartFill(frameEvent, intentBuilder(fullyQualifiedName)!!, "systemAnnotation"))
                    }
                }
            }

            if (isOpenIntent(frameEvent)) {
                // if it is supposed to trigger new intent, but it does not trigger it based on our rules, it is not allowed to trigger new intent in the following turns
                frameEvent.triggered = true
                frameEvent.typeUsed = true
            } else {
                val buildIntent = EventFrameBuilder(frameEvent)
                if (buildIntent.invoke(this) != null) {
                    return listOf(StartFill(frameEvent, buildIntent, "construct"))
                }
            }
        }

        if (schedule.state == Scheduler.State.RESCHEDULE) {
            return listOf(RescheduleAction())
        }

        // recover process
        if (schedule.state == Scheduler.State.RECOVER) {
            return listOf(RecoverAction())
        }
        return listOf()
    }

    // TODO(xiaobo): add the support for localization.
    // Instead of pass the UserSession anywhere, it might be better to use context oriented solution.
    // https://proandroiddev.com/an-introduction-context-oriented-programming-in-kotlin-2e79d316b0a2
    // Ideally all the builder specified template should be under with(session): so that we
    // can handle things like locale effectively.
    fun <T: Any> T.typeIdentifier() : String {
        return this::class.qualifiedName!!
    }

    fun <T: Any> T.typeName(): String {
        return chatbot!!.duMeta.getTriggers(this::class.qualifiedName!!).firstOrNull()?:typeIdentifier()
    }

    @Deprecated("")
    fun <T: Any> T.identifier() : String {
        return toString()
    }

    @Deprecated("")
    fun <T: Any> T.label() : String {
        return toString()
    }

    @Deprecated("")
    fun <T: Any> T.name() : String {
        // TODO(sean, xiaobo): If we need to test if it is entity, when it is not, we need to forward the call.
        val typeName = this::class.qualifiedName!!
        return chatbot!!.duMeta.getEntityInstances(typeName)[toString()]?.firstOrNull() ?: label()
    }

    /**
     * This is the new way of storing session global information, where
     * we identify things by fully qualified name.
     */
    @JsonIgnore
    val globals = LinkedHashMap<String, ISingleton>()

    @JsonIgnore
    override val finishedIntentFiller = mutableListOf<AnnotatedWrapperFiller>()

    // for support only.
    var botOwn: Boolean = true

    fun searchContext(candidateClass: List<String>): List<Any> {
        val result = mutableListOf<Any>()
        val candidatesFillers =
                mainSchedule.reversed().filterIsInstance<AnnotatedWrapperFiller>().filter { it.targetFiller is FrameFiller<*> } +
                mainSchedule.reversed().filterIsInstance<MultiValueFiller<*>>().filter { it.svType == MultiValueFiller.SvType.INTERFACE }.flatMap { it.fillers.mapNotNull { (it.targetFiller as? InterfaceFiller<*>)?.vfiller } } +
                finishedIntentFiller.filterIsInstance<AnnotatedWrapperFiller>().filter { (it.targetFiller as TypedFiller<*>).target.get() !is AbstractValueClarification<*> }
        val contextValueCandidates: List<Any> = candidatesFillers.flatMap {
            val res = mutableListOf<Any>()
            res.add((it.targetFiller as FrameFiller<*>).target.get()!!)
            res.addAll(it.targetFiller.fillers.values.filter { it.targetFiller is AEntityFiller || it.targetFiller is FrameFiller<*> }
                    .mapNotNull { (it.targetFiller as? TypedFiller<*>)?.target?.get() })
            res.addAll(it.targetFiller.fillers.values.filter { it.targetFiller is MultiValueFiller<*> }.flatMap {
                (it.targetFiller as MultiValueFiller<*>).fillers.mapNotNull { (it.targetFiller as? TypedFiller<*>)?.target?.get() } })
            res
        }.toSet().toList()
        val cachedKClass = mutableMapOf<String, KClass<*>>()
        for (c in candidateClass) {
            if (!cachedKClass.containsKey(c)) {
                try {
                    val kClass = findKClass(c)!!
                    cachedKClass[c] = kClass
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        for (target in contextValueCandidates) {
            for (c in candidateClass) {
                if (c == target::class.qualifiedName || (cachedKClass.containsKey(c) && cachedKClass[c]!!.isInstance(target))) {
                    result.add(target)
                }
            }
        }
        return result
    }

    // decide whether the active intent is IKernelIntent
    fun inKernelMode(s: List<IFiller>): Boolean {
        return s.any { it is FrameFiller<*> && it.frame() is IKernelIntent }
    }

    inline fun <reified T> getExtension(label: String = "default") : T? {
        val res = chatbot?.getExtension<T>(label)
        if (res is IProvider) {
            res.session = this
        }
        return res
    }

    fun generateFrameEvent(filler: IFiller, value: Any): List<FrameEvent> {
        val fullyQualifiedType: String = filler.qualifiedEventType() ?: if (value is ObjectNode) value.get("@class").asText() else value::class.qualifiedName!!
        val typeString = fullyQualifiedType.substringAfterLast(".")
        val packageName = fullyQualifiedType.substringBeforeLast(".")
        if (value is ObjectNode) value.remove("@class")
        val jsonElement = Json.encodeToJsonElement(value)
        return when {
            jsonElement is ValueNode || value is InternalEntity -> {
                listOf(FrameEvent.fromJson(typeString, Json.makeObject(mapOf(filler.attribute to jsonElement))).apply {
                    this.packageName = packageName
                })
            }
            jsonElement is ObjectNode -> {
                listOf(FrameEvent.fromJson(typeString, jsonElement).apply {
                    this.packageName = packageName
                })
            }
//            is ArrayNode -> {
//                jsonElement.mapNotNull {
//                    when (it) {
//                        is ValueNode -> {
//                            FrameEvent.fromJson(typeString, Json.makeObject(mapOf(filler.attribute to it))).apply {
//                                this.packageName = packageName
//                            }
//                        }
//                        is ObjectNode -> {
//                            FrameEvent.fromJson(typeString, it).apply {
//                                this.packageName = packageName
//                            }
//                        }
//                        else -> {
//                            null
//                        }
//                    }
//                }
//            }
            else -> {
                listOf()
            }
        }
    }

    fun findWrapperFillerForTargetSlot(frame: IFrame, slot: String?): AnnotatedWrapperFiller? {
        val filler = findWrapperFillerWithFrame(frame)
        return (if (slot.isNullOrEmpty() || slot == "this") filler else (filler?.targetFiller as? FrameFiller<*>)?.fillers?.get(slot))
    }

    fun findWrapperFillerWithFrame(frame: IFrame): AnnotatedWrapperFiller? {
        for (s in schedulers) {

            // search in all builder defined slots first
            val first = s.firstOrNull() ?: continue
            val path = findFillerPath(first) { f -> f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*> && f.targetFiller.frame() === frame }
            if (path.isNotEmpty()) return path.last() as AnnotatedWrapperFiller

            // search in all active fillers including fillers for VR, VC and Confirmation
            for (f in s) {
                if (f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*> && f.targetFiller.frame() === frame) {
                    return f
                }
            }
        }
        return null
    }

    fun findActiveFillerPathForTargetSlot(frame: IFrame, slot: String?): LinkedList<IFiller> {
        val path = findActiveFillerPathByFrame(frame)
        if (path.isNotEmpty() && !slot.isNullOrEmpty() && slot != "this") {
            val frameFiller = (path.last() as? AnnotatedWrapperFiller)?.targetFiller as? FrameFiller<*>
            val slotFiller = frameFiller?.get(slot) as? AnnotatedWrapperFiller
            if (frameFiller != null && slotFiller != null) {
                path += frameFiller
                path += slotFiller
            }
        }
        return path
    }

    // only finds active fillers, that is fillers direct in the stack including VR, VC and Confirmation fillers
    fun findActiveFillerPathByFrame(frame: IFrame): LinkedList<IFiller> {
        val path: LinkedList<IFiller> = LinkedList()
        for (s in schedulers) {
            for (f in s) {
                if (f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*> && f.targetFiller.frame() === frame) {
                    val index = s.indexOf(f)
                    path.addAll(s.subList(0, index+1))
                    return path
                }
            }
        }
        return path
    }

    fun isRightMostChild(p: AnnotatedWrapperFiller, c: AnnotatedWrapperFiller): Boolean {
        val targetFiller = p.targetFiller
        if (targetFiller is AEntityFiller) {
            return false
        } else if (targetFiller is FrameFiller<*>) {
            return c === targetFiller.fillers.values.lastOrNull()
        } else if (targetFiller is InterfaceFiller<*>) {
            return c === targetFiller.vfiller
        } else if (targetFiller is MultiValueFiller<*>) {
            return c === targetFiller.fillers.lastOrNull()
        }
        return false
    }

    fun hasNoChild(p: AnnotatedWrapperFiller): Boolean {
        val targetFiller = p.targetFiller
        if (targetFiller is AEntityFiller) {
            return true
        } else if (targetFiller is FrameFiller<*>) {
            return targetFiller.fillers.isEmpty()
        } else if (targetFiller is InterfaceFiller<*>) {
            return targetFiller.vfiller == null
        } else if (targetFiller is MultiValueFiller<*>) {
            return targetFiller.fillers.isEmpty()
        }
        return true
    }

    fun pushAllChildren(p: AnnotatedWrapperFiller, stack: Stack<AnnotatedWrapperFiller>) {
        val targetFiller = p.targetFiller
        if (targetFiller is FrameFiller<*>) {
            targetFiller.fillers.values.reversed().forEach { stack.push(it) }
        } else if (targetFiller is InterfaceFiller<*>) {
            stack.push(targetFiller.vfiller!!)
        } else if (targetFiller is MultiValueFiller<*>) {
            targetFiller.fillers.reversed().forEach { stack.push(it) }
        }
    }

    fun postOrderManipulation(scheduler: Scheduler, start: AnnotatedWrapperFiller, end: AnnotatedWrapperFiller, task: (AnnotatedWrapperFiller) -> Unit) {
        val root = scheduler.firstOrNull() as? AnnotatedWrapperFiller ?: return
        val stack = Stack<AnnotatedWrapperFiller>()
        stack.push(root)
        var last: AnnotatedWrapperFiller? = null
        var started: Boolean = false
        while (stack.isNotEmpty()) {
            val top = stack.peek()
            if ((last != null && isRightMostChild(top, last)) || hasNoChild(top)) {
                stack.pop()
                started =  started || top === start
                if (started) {
                    task(top)
                }
                last = top
                if (top === end) return
            } else {
                pushAllChildren(top, stack)
            }
        }
    }

    // only finds slot fillers; VR, VC, Confirmation fillers are filtered out
    fun findFillers(current: AnnotatedWrapperFiller?, res: MutableList<AnnotatedWrapperFiller>, filter: (AnnotatedWrapperFiller) -> Boolean, additionalBaseCase: (AnnotatedWrapperFiller) -> Boolean = { _ -> false}) {
        if (current == null || additionalBaseCase(current)) return
        if (filter(current)) {
            res += current
        }
        if (current.targetFiller is InterfaceFiller<*>) {
            findFillers(current.targetFiller.vfiller, res, filter, additionalBaseCase)
        } else if (current.targetFiller is FrameFiller<*>) {
            for (f in current.targetFiller.fillers.values) {
                findFillers(f, res, filter, additionalBaseCase)
            }
        } else if (current.targetFiller is MultiValueFiller<*>) {
            for (f in current.targetFiller.fillers) {
                findFillers(f, res, filter, additionalBaseCase)
            }
        }
    }

    // find filler path; VR, VC, Confirmation fillers are filtered out
    fun findFillerPath(current: IFiller?, filter: (IFiller) -> Boolean): LinkedList<IFiller> {
        var path: LinkedList<IFiller> = LinkedList()
        if (current == null) return path
        if (filter(current)) {
            path.offerFirst(current)
            return path
        }
        if (current is AnnotatedWrapperFiller) {
            path = findFillerPath(current.targetFiller, filter)
        } else if (current is InterfaceFiller<*>) {
            path = findFillerPath(current.vfiller, filter)
        } else if (current is FrameFiller<*>) {
            for (f in current.fillers.values) {
                path = findFillerPath(f, filter)
                if (path.isNotEmpty()) break
            }
        } else if (current is MultiValueFiller<*>) {
            for (f in current.fillers) {
                path = findFillerPath(f, filter)
                if (path.isNotEmpty()) break
            }
        }
        if (path.isNotEmpty()) path.offerFirst(current)
        return path
    }

    fun construct(packageName: String?, className: String, vararg args: Any?): IFrame? {
        val revisedPackageName = packageName ?: chatbot?.javaClass?.packageName
        try {
            val kClass = Class.forName("${revisedPackageName}.${className}", true, chatbot!!.getLoader()).kotlin
            val ctor = kClass.primaryConstructor ?: return null
            // Checking whether this is singleton.
            return ctor.call(*args) as? IFrame
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } catch (e: Error) {
            return null
        }
    }


    fun findKClass(className: String): KClass<*>? {
        return try {
            when (className) {
                "kotlin.Int" -> Int::class
                "kotlin.Float" -> Float::class
                "kotlin.String" -> String::class
                "kotlin.Boolean" -> Boolean::class
                else -> {
                    val kClass = Class.forName(className, true, chatbot!!.getLoader()).kotlin
                    kClass
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // refocus here means refocus on both filled slots and unfilled slots; depends on conditions
    // search from top of stack and involve children within one level from active filler
    override fun tryToRefocus(frameEvents: List<FrameEvent>): Pair<List<IFiller>, FrameEvent>? {
        if (schedule.isEmpty()) return null
        val stack: LinkedList<IFiller> = LinkedList()
        for (f in schedule) {
            stack.offerLast(f)
        }

        var last: IFiller? = null
        while (stack.isNotEmpty()) {
            val top = stack.peekLast() as? AnnotatedWrapperFiller
            if (top == null || top.targetFiller !is FrameFiller<*>) {
                stack.pollLast()
                continue
            }
            searchForRefocusChild(top.targetFiller, frameEvents, 0)?.let {
                stack.offerLast(top.targetFiller)
                stack.offerLast(it.first)
                return Pair(stack, it.second)
            }

            for (c in top.targetFiller.fillers.values.filter { it.targetFiller is FrameFiller<*> && it != last }) {
                searchForRefocusChild(c.targetFiller as FrameFiller<*>, frameEvents, 1)?.let {
                    stack.offerLast(top.targetFiller)
                    stack.offerLast(c)
                    stack.offerLast(c.targetFiller)
                    stack.offerLast(it.first)
                    return Pair(stack, it.second)
                }
            }
            last = stack.pollLast()
        }
        return null
    }

    private fun searchForRefocusChild(
        parent: FrameFiller<*>,
        frameEvents: List<FrameEvent>,
        level: Int
    ): Pair<AnnotatedWrapperFiller, FrameEvent>? {
        // conditions on which we allow refocus;
        // 1. filled slots of all candidate frames
        // 2. unfinished mv filler with HasMore FrameEvent (especially for the case in which we focus on PagedSelectable and user wants to say no to mv slot)
        // 3. unfilled slots of active frames (level == 0)
        //  (1) prevent the expression "to shanghai" from triggering new Intent
        //  (2) refocus to skill: IIntent (Intent Suggestion) excluding partially filled Interface type
        //  (3) refocus to entry filler of MultiValueFiller after we infer a hasMore.Yes. Excluding refocus to MultiValueFiller if there is entry filler not done
        val matcher: (AnnotatedWrapperFiller) -> Boolean = {
            val targetFiller = it.targetFiller
            it.canEnter(frameEvents)
                    && ((targetFiller is AEntityFiller && targetFiller.done() && frameEvents.firstOrNull { e -> it.isCompatible(e) } != null)
                        // special matcher for HasMore with PagedSelectable; allow to refocus to undone HasMore if there is one
                        || (targetFiller is InterfaceFiller<*> && (it.parent as? FrameFiller<*>)?.frame() is HasMore && (it.parent?.parent?.parent as? MultiValueFiller<*>)?.done() == false && frameEvents.firstOrNull { e -> it.isCompatible(e) } != null)
                        || (level == 0
                            && (targetFiller is AEntityFiller || (targetFiller is InterfaceFiller<*> && targetFiller.realtype == null) || (targetFiller is MultiValueFiller<*> && targetFiller.findCurrentFiller() == null))
                            && !targetFiller.done(frameEvents) && frameEvents.firstOrNull { e -> it.isCompatible(e) } != null)
                    )
        }
        // open slots take priority
        val groups = parent.fillers.values.groupBy { it.done(frameEvents) }
        val filler = groups[false]?.firstOrNull(matcher)
                ?: groups[true]?.firstOrNull(matcher)
                ?: return null
        val e = frameEvents.first { e -> filler.isCompatible(e) }
        return Pair(filler, e)
    }

    override fun getActionByEvent(frameEvents: List<FrameEvent>): Action? {
        for (f in schedule.reversed()) {
            if (f is AnnotatedWrapperFiller && f.targetFiller is FrameFiller<*>) {
                val contextFrame = f.targetFiller.frame()
                for (event in frameEvents) {
                    val stateUpdateIntentBuilder = contextFrame.searchStateUpdateByEvent(event.fullType)
                    if (stateUpdateIntentBuilder != null) return StartFill(event, stateUpdateIntentBuilder, "stateupdate")
                }
            }
        }
        return null
    }

    fun findSystemAnnotation(systemAnnotationType: SystemAnnotationType, vararg args: Any?): IIntent? {
        val fullyQualifiedName: String = systemAnnotationType.typeName
        if (fullyQualifiedName.isEmpty()) return null
        val index = fullyQualifiedName.lastIndexOf(".")
        if (index < 0) return null
        val packageName = fullyQualifiedName.substring(0, index)
        val className = fullyQualifiedName.substring(index + 1)
        return construct(packageName, className, this, *args) as? IIntent
    }

    private fun genGroupKey(dialogAct: ComponentDialogAct): String {
        return when (dialogAct) {
            is SlotDialogAct -> """${if (dialogAct.context.isNotEmpty()) dialogAct.context.first()::class.qualifiedName else "null"}_${dialogAct.slotName}_${dialogAct.slotType}"""
            is FrameDialogAct -> dialogAct.frameType
            else -> throw Exception("ComponentDialogAct not supported")
        }
    }

    private fun areParametersCompatible(formalParams: List<KParameter>, actualParams: List<ComponentDialogAct>): Boolean {
        check(formalParams.size == actualParams.size)
        for (i in formalParams.indices) {
            if ((formalParams[i].type.classifier as? KClass<*>)?.isInstance(actualParams[i]) != true) return false
        }
        return true
    }

    private fun findDialogActCustomization(dialogAct: ComponentDialogAct): Templates? {
        if (dialogAct is SlotDialogAct) {
            val annotations = dialogAct.context.firstOrNull()?.findAll<DialogActCustomizationAnnotation>(dialogAct.slotName) ?: listOf()
            return annotations.firstOrNull { it.dialogActName == dialogAct::class.qualifiedName }?.templateGen?.invoke(dialogAct)
        } else if (dialogAct is FrameDialogAct) {
            val packageName = dialogAct.frameType.substringBeforeLast(".")
            val className = dialogAct.frameType.substringAfterLast(".")
            val annotations = construct(packageName, className, this)?.findAll<DialogActCustomizationAnnotation>("this") ?: listOf()
            return annotations.firstOrNull { it.dialogActName == dialogAct::class.qualifiedName }?.templateGen?.invoke(dialogAct)
        } else {
            throw Exception("ComponentDialogAct not supported")
        }
    }

    private fun rewriteDialogActInGroup(group: List<ComponentDialogAct>): List<ComponentDialogAct> {
        val res = mutableListOf<ComponentDialogAct>()
        val constructors = chatbot!!.rewriteRules.map { it.primaryConstructor!! }.sortedByDescending { it.parameters.size }
        var index = 0
        while (index < group.size) {
            var combined: Boolean = false
            for (constructor in constructors) {
                if (index + constructor.parameters.size > group.size) continue
                if (areParametersCompatible(constructor.parameters, group.subList(index, index+constructor.parameters.size))) {
                    val r = (constructor.call(*group.toTypedArray())).result
                    findDialogActCustomization(r)?.let {
                        r.templates = it
                    }
                    res += r
                    index += constructor.parameters.size
                    combined = true
                    break
                }
            }
            if (!combined) {
                res += group[index]
                index++
            }
        }
        return res
    }

    fun rewriteDialogAct(dialogActList: List<ComponentDialogAct>): List<ComponentDialogAct> {
        val groups: MutableList<Pair<String, MutableList<ComponentDialogAct>>> = mutableListOf()
        for (dialogAct in dialogActList) {
            val  key = genGroupKey(dialogAct)
            if (groups.isEmpty() || groups.last().first != key) {
                groups += Pair(key, mutableListOf(dialogAct))
            } else {
                groups.last().second += dialogAct
            }
        }
        return groups.map { rewriteDialogActInGroup(it.second) }.flatten()
    }

    fun makeSingleton(qname: String) {
        if (!globals.containsKey(qname)) {
            val kClass = Class.forName(qname, true, chatbot!!.getLoader()).kotlin
            val ctor = kClass.primaryConstructor ?: return
            val frame = ctor.call(this) as? ISingleton
            if (frame != null) {
                frame.filler = frame.createBuilder().invoke(ParamPath(frame))
                globals[qname] = frame
            }
        }
    }

    fun getOpenPayloadIntent(): String? {
        for (s in schedulers.reversed()) {
            val intent = (s.lastOrNull { it is FrameFiller<*> && it.frame() is IIntent && !it.frame()::class.qualifiedName!!.startsWith("io.opencui") } as? FrameFiller<*>)?.frame()
            if (intent != null) return intent::class.qualifiedName
        }
        return null
    }

    fun isOpenIntent(event: FrameEvent): Boolean {
        return schedule.firstOrNull { it is AnnotatedWrapperFiller && it.targetFiller is FrameFiller<*> && it.targetFiller.frame()::class.qualifiedName == event.fullType } != null
    }

    inline fun <reified T : ISingleton> getGlobal(): T? {
        val qname = T::class.qualifiedName!!
        makeSingleton(qname)
        return globals[qname] as T?
    }

    inline fun <reified T : ISingleton> getGlobalFiller(): FrameFiller<T>? {
        val qname = T::class.qualifiedName!!
        makeSingleton(qname)
        return globals[qname]?.filler as? FrameFiller<T>
    }

    fun cleanup() {
        while (schedulers.size > 1) {
            schedulers.removeLast()
        }
        mainSchedule.cleanup()
        events.clear()
        turnId = 0
        globals.clear()
        finishedIntentFiller.clear()
    }

    fun toSessionString(): String {
        val objNode = ObjectNode(JsonNodeFactory.instance)
        objNode.replace("schedulers_count", IntNode(schedulers.size))
        objNode.replace("main", mainSchedule.toObjectNode())
        return Json.encodeToString(objNode)
    }

    @kotlin.jvm.Throws(Exception::class)
    private fun readObject(ois: ObjectInputStream) {
        ois.defaultReadObject()
        chatbot = ChatbotLoader.findChatbotByQualifiedName(botInfo)
    }

    companion object {
        val USERIDENTIFIER = io.opencui.core.user.UserIdentifier::class.qualifiedName!!
        private val serialVersionUID: Long = 123
        val PACKAGE = USERIDENTIFIER.split(".").subList(0, 2).joinToString(".")
    }
}
