package io.opencui.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.opencui.core.da.ComponentDialogAct
import io.opencui.core.da.SlotOffer
import io.opencui.core.da.SlotRequest
import io.opencui.core.da.UserDefinedInform
import io.opencui.serialization.Json
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.full.isSubclassOf

data class IDonotGetIt(override var session: UserSession? = null) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf()

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { """I did not get that.""" } )) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: IDonotGetIt? = this@IDonotGetIt

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}

data class IntentSuggestion(override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type = FrameKind.SMALLINTENT

    var intentPackage: String? = null
    var intentName: String? = null

    @JsonIgnore
    fun getSuggestions(): List<IntentSuggestion> {
        return listOf(
            IntentSuggestion().apply {
                intentPackage = "io.opencui.test"
                intentName = "BookHotel"
            },
            IntentSuggestion().apply {
                intentPackage = "io.opencui.core"
                intentName = "IDonotKnowWhatToDo"
            }
        )
    }

    @JsonIgnore
    var recommendation: PagedSelectable<IntentSuggestion> = PagedSelectable(
        session, {getSuggestions()}, { IntentSuggestion::class },
        {offers -> SlotOffer(offers, "this", "io.opencui.core.IntentSuggestion",
                simpleTemplates(listOf(LazyEvalPrompt {"""We have following ${offers.size} choices for intents : ${offers.joinToString(", ") {
            "(${it.intentPackage}, ${it.intentName})" }}."""})))
        },
        target = this, slot = "")

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "intentPackage" to listOf(
            SlotPromptAnnotation(listOf(TextOutputAction({ SlotRequest("intentPackage", "kotlin.String", simpleTemplates(LazyEvalPrompt { "Which package?" })) }))),
            ValueRecAnnotation({recommendation}, false)),
        "intentName" to listOf(
            SlotPromptAnnotation(listOf(TextOutputAction({ SlotRequest("intentName", "kotlin.String", simpleTemplates(LazyEvalPrompt { "Which intent?" })) }))))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:IntentSuggestion? = this@IntentSuggestion

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({target.get()!!::intentPackage}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({target.get()!!::intentName}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            // TODO(xiaobo): why we are triggering this intent action?
            else -> IntentAction(JsonFrameBuilder("{\"@class\": \"${this.intentPackage ?: ""}.${this.intentName ?: ""}\"}", listOf(session)))
        }
    }
}

data class IDonotKnowWhatToDo(override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: IDonotKnowWhatToDo?= this@IDonotKnowWhatToDo
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { """I do not know what to do now.""" } )) })
        }
    }
}

data class IntentName(@get:JsonIgnore override var value: String): InternalEntity {
    override var origValue: String? = null
    @JsonValue
    override fun toString() : String = value
}

data class AbortIntent(override var session: UserSession? = null): AbstractAbortIntent(session) {
    override val builder: (String) -> InternalEntity? = { Json.decodeFromString<IntentName>(it)}
    override val defaultFailPrompt: (() -> ComponentDialogAct)? = { UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { """Failed to abort!""" })) }
    override val defaultSuccessPrompt: (() -> ComponentDialogAct)? = { UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { with(session!!) {"""${intent?.typeName()} is Aborted successfully!"""} })) }
    override val defaultFallbackPrompt: (() -> ComponentDialogAct)? = { UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { """Aborted ancestor intent""" })) }
}

data class ValueClarification<T: Any>(
    override var session: UserSession? = null,
    override val getClass: () -> KClass<T>,
    override val source: MutableList<T>,
    override var targetFrame: IFrame,
    override var slot: String): AbstractValueClarification<T>(session, getClass, source, targetFrame, slot) {

    @JsonIgnore
    override var target: T? = if (getClass().isSubclassOf(IFrame::class)) getClass().constructors.first().call(session) else null

    @JsonIgnore
    override fun _rec_target(it: T?): PagedSelectable<T> = PagedSelectable(
        session,  {source}, getClass,
        {offers -> SlotOffer(offers, "target", getClass().qualifiedName!!,
                    simpleTemplates(listOf(LazyEvalPrompt {with(session!!){"""by ${targetSlotAlias()}, which do you mean: ${offers.joinToString(", ") {
                        "(${it.name()})" }}."""}})))
        },
        pageSize = 5, target = this, slot = "target")

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mapOf(
        "target" to listOf(SlotPromptAnnotation(listOf(TextOutputAction({ SlotRequest("target", getClass().qualifiedName!!, simpleTemplates(LazyEvalPrompt { "target?" })) }))), TypedValueRecAnnotation<T>({_rec_target(this)})))
}

data class ResumeIntent(override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var intent: IIntent? = null

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "intent" to listOf(NeverAsk())
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: ResumeIntent?= this@ResumeIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(InterfaceFiller({ frame!!::intent }, createFrameGenerator(frame!!.session!!, "io.opencui.core.IIntent")))
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { with(session!!){"We are in the middle of ${intent?.typeName()} already, let's continue with the current process."} } )) })
        }
    }
}

// hardcode for clean session
data class CleanSession(override var session: UserSession? = null) : IIntent {
    @JsonIgnore
    override val type = FrameKind.SMALLINTENT

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: CleanSession? = this@CleanSession
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> CloseSession()
        }
    }
}
