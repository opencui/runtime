package io.opencui.core


import io.opencui.core.da.ComponentDialogAct
import io.opencui.core.da.SlotRequest
import java.io.Serializable

/**
 * Dialog behaviors are controlled by largely by annotations, but some of these
 * annotations never makes to dm, and they are fixed IDE/Compiler time (for example,
 * companion attribute, and also training phrases).
 *
 * For rest of the annotation, dm is used to carry out the conversation based on the user
 * input and developer supplied annotation, predefined system rules.
 */

interface Annotation: Serializable {
    val switch: () -> Boolean
        get() = {true}
}

interface IPrompt: () -> String, Serializable

interface ICondition: () -> Boolean, Serializable

// codegen needs this for DialogAct
data class StaticPrompt (
        private val s: String
) : IPrompt {
    override fun invoke() = s
}

data class LazyEvalPrompt (
    private val f: () -> String
) : IPrompt {
    override fun invoke() = f()
}

data class LazyEvalCondition (
    private val f: () -> Boolean
): ICondition {
    override fun invoke() = f()
}

interface TablePrompt<in T>: Serializable {
    operator fun invoke(table: List<T>) : String
}


fun <T> tablePrompt(f: (List<T>) -> String) : TablePrompt<T> = object : TablePrompt<T> {
    override fun invoke(table: List<T>) : String { return f(table) }
}

interface ParamPrompt<in T>: Serializable {
    operator fun invoke(obj: T): String
}

fun <T> paramPrompt(f: (T) -> String): ParamPrompt<T> = object : ParamPrompt<T> {
    override fun invoke(obj: T): String {
        return f(obj)
    }
}


// This has two sides: how it is used and how it is created, and also just a type.
data class Templates(val channelPrompts: Map<String, List<IPrompt>>): Serializable {
    constructor(prompts: List<IPrompt>): this(mapOf(SideEffect.RESTFUL to prompts))
    constructor(vararg pprompts: IPrompt) : this(pprompts.asList())
    fun pick(channel: String = SideEffect.RESTFUL): IPrompt {
        val prompts = channelPrompts[channel] ?: channelPrompts[SideEffect.RESTFUL] ?: return LazyEvalPrompt { "" }
        return if (prompts.isNotEmpty()) prompts.random() else LazyEvalPrompt { "" }
    }
}

fun defaultTemplate() = Templates(mapOf())
fun simpleTemplates(vararg pprompts: IPrompt) = Templates(pprompts.asList())
fun simpleTemplates(promptList: List<IPrompt>) = Templates(promptList)
fun simpleTemplates(vararg texts: String) = Templates(texts.asList().map{ LazyEvalPrompt{it} })
fun simpleTemplates(vararg ops: () -> String) = Templates(ops.asList().map{ LazyEvalPrompt(it) })
fun simpleTemplates(channelPrompts: Map<String, List<IPrompt>>) = Templates(channelPrompts)

fun <T> convertDialogActGen(source: () -> T, dialogActGen: (T) -> ComponentDialogAct): () -> ComponentDialogAct {
    return {dialogActGen(source())}
}

interface PromptAnnotation : Annotation {
    val actions: List<Action>
}

/**
 * If we want agent ask user for value to fill the slot, then developer can provide
 * the prompt questions via this annotation. Ideally, we can potentially add exemplars
 * for the answers as well.
 */
data class SlotPromptAnnotation(override val actions: List<Action>) : PromptAnnotation {
    // just for convenience of testcase
    constructor(templates: Templates): this(listOf(TextOutputAction { SlotRequest("", "", templates) }))
}

data class SlotConditionalPromptAnnotation(override val actions: List<Action>) : PromptAnnotation {
    // just for convenience of testcase
    constructor(picker: () -> Templates): this(listOf(LazyPickAction { TextOutputAction { SlotRequest("", "", picker()) } }))
}

data class SlotInformActionAnnotation(override val actions: List<Action>) : PromptAnnotation

interface AskStrategy: Annotation {
    fun canEnter(): Boolean
}

data class AlwaysAsk(val condition: Boolean = true): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

data class ExternalEventStrategy(val condition: Boolean = true): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

data class NeverAsk(val condition: Boolean = true): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

data class ConditionalAsk(val condition: ICondition): AskStrategy {
    override fun canEnter(): Boolean {
        return condition()
    }
}

data class RecoverOnly(var condition: Boolean = false): AskStrategy {
    override fun canEnter(): Boolean {
        return condition
    }

    fun enable() {
        condition = true;
    }

    fun disable() {
        condition = false;
    }
}

data class BoolGateAsk(val generator: () -> ComponentDialogAct): AskStrategy {
    override fun canEnter(): Boolean {
        return true
    }
}

interface IValueRecAnnotation: Annotation

data class ValueRecAnnotation(val recFrameGen: () -> IFrame, val showOnce: Boolean = false): IValueRecAnnotation

data class TypedValueRecAnnotation<T>(val recFrameGen: T?.() -> IFrame, val showOnce: Boolean = false): IValueRecAnnotation

data class ConfirmationAnnotation(val confirmFrameGetter: ()->IFrame?): Annotation

data class ValueCheckAnnotation(val checkFrame: IFrame, override val switch: () -> Boolean = {true}): Annotation

data class MinMaxAnnotation(val min: Int, val minGen: () -> ComponentDialogAct, val max: Int, val maxGen: () -> ComponentDialogAct): Annotation

data class SlotInitAnnotation(val action: Action): Annotation

data class SlotDoneAnnotation(val condition: () -> Boolean, val actions: List<Action>): Annotation

data class DialogActCustomizationAnnotation(val dialogActName: String, val templateGen: (ComponentDialogAct) -> Templates): Annotation


enum class SystemAnnotationType(val typeName: String) {
    IDonotGetIt("io.opencui.core.IDonotGetIt"),
    IntentSuggestion("io.opencui.core.IntentSuggestion"),
    ValueClarification("io.opencui.core.ValueClarification"),
    ResumeIntent("io.opencui.core.ResumeIntent"),
}

