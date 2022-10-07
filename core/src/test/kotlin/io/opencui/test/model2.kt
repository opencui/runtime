package io.opencui.test

import io.opencui.core.*
import io.opencui.core.Annotation
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.opencui.core.da.*
import io.opencui.serialization.Json
import java.io.Serializable
import kotlin.reflect.KMutableProperty0



data class MultiValueEntityRecIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT


    @JsonIgnore
    val recommendation = PayMethod.createRecFrame(session!!, this, "payMethodList")

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation({
                if (payMethodList!!.isEmpty()) simpleTemplates({ "payMethod?\n" })
                else simpleTemplates( { "anything else?\n" })}),
            ValueRecAnnotation({recommendation}, false)),
    )

    var payMethodList: MutableList<PayMethod>? = null

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MultiValueEntityRecIntent? = this@MultiValueEntityRecIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MultiValueEntityRecIntent_0(this)
        }
    }
}

data class MultiValueEntityRecIntent_0(
        val frame: MultiValueEntityRecIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, size = ${payMethodList?.size}""" }})) })

data class MultiValueFrameRecIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT


    @JsonIgnore
    var recHotels = Hotel(session)

    @JsonIgnore
    val recommendation = PagedSelectable<Hotel>(
        session, {recHotels.vacationService.searchHotel()}, { Hotel::class },
            {offers -> SlotOffer(offers, "hotels", "kotlin.collections.List<io.opencui.test.Hotel>",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it.hotel})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "hotels")

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "hotels" to listOf(
            SlotConditionalPromptAnnotation({
                if (hotels!!.isEmpty()) simpleTemplates( { "hotel?\n" })
                else simpleTemplates( { "any hotel else?\n" })}),
            ValueRecAnnotation({recommendation}, false)),
    )


    var hotels: MutableList<Hotel>? = null

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MultiValueFrameRecIntent? = this@MultiValueFrameRecIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::hotels },
                fun(p: KMutableProperty0<Hotel?>): ICompositeFiller {
                    val builder = p.apply { set(Hotel(frame!!.session)) }.get()!!.createBuilder()
                    return builder.invoke(path.join("hotels.item", p.get()))
                })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MultiValueFrameRecIntent_0(this)
        }
    }
}

data class MultiValueFrameRecIntent_0(
        val frame: MultiValueFrameRecIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, size = ${hotels?.size}""" }})) })

data class InternalNode(@get:JsonIgnore var value: String): Serializable {
    var origValue: String? = null
    @JsonValue
    override fun toString() : String = value
}


data class InternalNodeIntent(
        override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var current: InternalNode? = null
    @JsonIgnore
    var skill: IIntent? = null

    @get:JsonIgnore
    val searchIntentsService: IIntentSuggestionService
        get() {
            return session!!.getExtension<IIntentSuggestionService>() as IIntentSuggestionService
        }

    @JsonIgnore
    var recommendation: PagedSelectable<IIntent> = PagedSelectable(
        session, {searchIntentsService.searchIntentsByCurrent(current)}, { IIntent::class },
            {offers -> SlotOffer(offers, "skill", "io.opencui.core.IIntent",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session!!){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it.typeName()})" }}."""}})))
            },
        target = this, slot = "skill")

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("skill" to listOf(SlotPromptAnnotation(simpleTemplates( {"""What can I do for you? (InternalNodeIntent)"""})), ValueRecAnnotation({recommendation}, false)))

    override fun searchResponse(): Action? = when {
        else -> null
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: InternalNodeIntent? = this@InternalNodeIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::current}) { s -> Json.decodeFromString(s)})
            filler.addWithPath(InterfaceFiller({ frame!!::skill }, createFrameGenerator(frame!!.session!!, "io.opencui.core.IIntent")))
            return filler
        }
    }
}

data class EntityRecSelection(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var payMethod: PayMethod? = null

    @JsonIgnore
    val recommendation: PagedSelectable<PayMethod> = PayMethod.createRecFrame(this.session!!, this, "payMethod")

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf("payMethod" to listOf(SlotPromptAnnotation(simpleTemplates( {"payMethod?\n"})), ValueRecAnnotation({recommendation}, false)))

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:EntityRecSelection? = this@EntityRecSelection
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller<PayMethod>({frame!!::payMethod}, {s: String? -> payMethod?.origValue = s}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> EntityRecSelection_0(this)
        }
    }
}

data class EntityRecSelection_0(
        val frame: EntityRecSelection
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, pay method = ${payMethod}""" }})) })

data class ShowOnceRecommendation(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    @JsonIgnore
    val recommendation = PayMethod.createRecFrame(session!!, this, "payMethodList")

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation({if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" }) else simpleTemplates( { "any payMethod else?" })}),
            ValueRecAnnotation({recommendation}, true)),
    )

    var payMethodList: MutableList<PayMethod>? = null

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ShowOnceRecommendation? = this@ShowOnceRecommendation
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ShowOnceRecommendation_0(this)
        }
    }
}

data class ShowOnceRecommendation_0(
    val frame: ShowOnceRecommendation
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, pay method = ${payMethodList?.size}""" }})) })

data class MultiValueValueCheck(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    fun checker():Boolean {
        return payMethodList!!.firstOrNull { it.value == "visa" } != null
    }

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation({
                if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" })
                else simpleTemplates( { "any payMethod else?" })}) ,
            ValueCheckAnnotation(OldValueCheck(session, {checker()}, listOf(Pair(this, "payMethodList")),
                    { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.VC, simpleTemplates(LazyEvalPrompt { "payMethodList check failed, size = ${payMethodList!!.size}" })) }
            ))))

    var payMethodList: MutableList<PayMethod>? = null

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MultiValueValueCheck? = this@MultiValueValueCheck
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MultiValueValueCheck_0(this)
        }
    }
}

data class MultiValueValueCheck_0(
        val frame: MultiValueValueCheck
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, pay method = ${payMethodList?.size}""" }})) })

data class SepTestIntentExplicit(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT
    var a: Boolean? = null

    @JsonIgnore
    val _rec_b = PagedSelectable<Int>(
        session, {recInt()}, { Int::class },
            {offers -> SlotOffer(offers, "b", "kotlin.Int",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices for you : ${offers.joinToString(", ") {
                        "($it)" }}."""}})))
            },
        target = this, slot = "b", hard = true,
        singleEntryPrompt = { SlotOfferSepInform(it, "b", "kotlin.Int", simpleTemplates(LazyEvalPrompt {"sep confirmation b=${it}, contextC=${c}"})) }
    )

    var b: Int? = null
    var c: String? = null

    fun recInt(): List<Int> {
        return listOf(2)
    }

    @JsonIgnore
    val confirmb = Confirmation(session, this, "b",
            { SlotConfirm(this, "b", "kotlin.Int", simpleTemplates(LazyEvalPrompt {"original confirmation b=${b}"})) }
    )

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( {"a ?"}))),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( {"b ?"})),
            ValueRecAnnotation({_rec_b}, false),
            ConfirmationAnnotation({searchConfirmation("b")})
        ),
        "c" to listOf(SlotPromptAnnotation(simpleTemplates( {"c ?"}))),
    )

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "b" -> confirmb
            else -> null
        }
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:SepTestIntentExplicit? = this@SepTestIntentExplicit
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::c}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> SepTestIntent_0(this)
        }
    }
}

data class SepTestIntent_0(
    val frame: SepTestIntentExplicit
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, a=${a}, b=${b}, c=${c}""" }})) })

data class SepTestIntentImplicit(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT
    var a: Boolean? = null

    @JsonIgnore
    val _rec_b = PagedSelectable<Int>(
        session, {recInt()}, { Int::class },
            {offers -> SlotOffer(offers, "b", "kotlin.Int",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices for you : ${offers.joinToString(", ") {
                        "($it)" }}."""}})))
            },
        target = this, slot = "b", hard = true,
        singleEntryPrompt = { SlotOfferSepInform(it, "b", "kotlin.Int", simpleTemplates(LazyEvalPrompt {"sep confirmation b=${it}, contextC=${c}"})) },
        implicit = false
    )

    var b: Int? = null
    var c: String? = null

    fun recInt(): List<Int> {
        return listOf(2)
    }

    @JsonIgnore
    val confirmb = Confirmation(session, this, "b",
            { SlotConfirm(this, "b", "kotlin.Int", simpleTemplates(LazyEvalPrompt {"original confirmation b=${b}"})) }
    )

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( {"a ?"}))),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( {"b ?"})),
            ValueRecAnnotation({_rec_b}, false),
            ConfirmationAnnotation({searchConfirmation("b")})
        ),
        "c" to listOf(SlotPromptAnnotation(simpleTemplates( {"c ?"}))),
    )

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "b" -> confirmb
            else -> null
        }
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:SepTestIntentImplicit? = this@SepTestIntentImplicit
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::c}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> SepTestIntentImplicit_0(this)
        }
    }
}

data class SepTestIntentImplicit_0(
    val frame: SepTestIntentImplicit
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, a=${a}, b=${b}, c=${c}""" }})) })

data class MultiValueMinMax(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var payMethodList: MutableList<PayMethod>? = null

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation() { if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" })
            else simpleTemplates( { "anything else?" }) },
            MinMaxAnnotation(1, { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.MIN, simpleTemplates( { "size = ${payMethodList!!.size} less than 1" })) },
                    2, { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.MAX, simpleTemplates( { "size = ${payMethodList!!.size} greater than 2" })) }),
            ValueCheckAnnotation(MaxValueCheck(session, {payMethodList}, 2,
                    { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.VC, simpleTemplates( { "size = ${payMethodList!!.size} greater than 2" })) }
            ))
        )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MultiValueMinMax? = this@MultiValueMinMax
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MultiValueMinMax_0(this)
        }
    }
}

data class MultiValueMinMax_0(
    val frame: MultiValueMinMax
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, size = ${payMethodList?.size}""" }})) })

data class MultiValueMinMaxWithRec(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var payMethodList: MutableList<PayMethod>? = null

    fun recData(): List<PayMethod> {
        return listOf(PayMethod("visa"))
    }

    val rec = PagedSelectable(
        session, {recData()}, { PayMethod::class },
            {offers -> SlotOffer(offers, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session!!){"""We have following ${offers.size} choices for PayMethod : ${offers.joinToString(", ") {
                        "${it.name()}" }}."""}})))
            },
        target = this, slot = "payMethodList", hard = true, zeroEntryActions = listOf(),
        singleEntryPrompt = { SlotOfferSepInform(it, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", simpleTemplates(LazyEvalPrompt { with(session!!){"""chose pay method ${it.name()} for you"""}})) },
        implicit = true, autoFillSwitch = {payMethodList!!.size < 2})

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation { if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" }) else
                simpleTemplates( { "anything else?" }) },
            MinMaxAnnotation(2,
                    { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${payMethodList!!.size} less than 2" })) },
                    3,
                    { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${payMethodList!!.size} greater than 3" })) }
            ),
            ValueCheckAnnotation(MaxValueCheck(session, {payMethodList}, 3,
                    { SlotNotifyFailure(payMethodList, "payMethodList", "kotlin.collections.List<io.opencui.test.PayMethod>", FailType.VC, simpleTemplates(LazyEvalPrompt { "size = ${payMethodList!!.size} greater than 3" })) }
            )),
            ValueRecAnnotation({rec})
        )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MultiValueMinMaxWithRec? = this@MultiValueMinMaxWithRec
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MultiValueMinMaxWithRec_0(this)
        }
    }
}

data class MultiValueMinMaxWithRec_0(
    val frame: MultiValueMinMaxWithRec
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, size = ${payMethodList?.size}""" }})) })

data class ValueCheckSwitchTest(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var a: Int? = null
    var b: Boolean? = null
    var c: String? = null

    val valueCheck_a = OldValueCheck(session, {a != 1}, listOf(Pair(this, "a")),
            { SlotNotifyFailure(a, "a", "kotlin.Int", FailType.VC, simpleTemplates(LazyEvalPrompt { """no such a = ${a}""" })) }
    )
    val valueCheck_ab = OldValueCheck(session, {a!! > 2 && b == true}, listOf(Pair(this, "a"), Pair(this, "b")),
            { SlotNotifyFailure(b, "b", "kotlin.Boolean", FailType.VC, simpleTemplates(LazyEvalPrompt { """no such combination of a = ${a} b = ${b}""" })) }
    )

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( { """a?""" })), ValueCheckAnnotation(valueCheck_a)),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( { """b?""" })), ValueCheckAnnotation(valueCheck_ab)),
        "c" to listOf(SlotPromptAnnotation(simpleTemplates( { """c?""" })))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ValueCheckSwitchTest? = this@ValueCheckSwitchTest
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::c}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ValueCheckSwitchTest_0(this)
        }
    }
}

data class ValueCheckSwitchTest_0(
        val frame: ValueCheckSwitchTest
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates( {"""Hi, a = ${frame.a} b = ${frame.b} c = ${frame.c}""" })) })

data class City(@get:JsonIgnore override var value: String): InternalEntity, Serializable {
    override var origValue: String? = null
    @JsonValue
    override fun toString() : String = value

    companion object {

        val normalizedFormMap = Agent.duMeta.getEntityInstances(City::class.qualifiedName!!)

        fun getAllInstances(): List<City> {
            return normalizedFormMap.map { City(it.key) }
        }
    }
}

data class CollectCities(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var cities: MutableList<City>? = null

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "cities" to listOf(SlotConditionalPromptAnnotation({if (cities!!.isEmpty()) simpleTemplates( { "city?" }) else simpleTemplates( { "any city else?" })})))

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:CollectCities? = this@CollectCities
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            val msfiller = MultiValueFiller(
                { frame!!::cities },
                fun(p: KMutableProperty0<City?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> CollectCities_0(this)
        }
    }
}

data class CollectCities_0(
        val frame: CollectCities
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, cities size = ${frame.cities?.size}""" }})) })

data class BookTrain(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var departure: City? = null
    var arrival: City? = null
    var placeHolder: String? = null


    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "departure" to listOf(SlotPromptAnnotation(simpleTemplates( { """departure?""" }))),
        "arrival" to listOf(SlotPromptAnnotation(simpleTemplates( { """arrival?""" }))),
        "placeHolder" to listOf(SlotPromptAnnotation(simpleTemplates( { """placeHolder?""" })))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:BookTrain? = this@BookTrain
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::departure}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::arrival}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::placeHolder}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> BookTrain_0(this)
        }
    }
}

data class BookTrain_0(
    val frame: BookTrain
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, departure = ${frame.departure} arrival = ${frame.arrival}""" }})) })

data class WeatherConsult(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var city: City? = null

    fun weather(): String? {
        return when (city?.value) {
            "Beijing" -> "cloudy"
            "Shenzhen" -> "sunny"
            else -> null
        }
    }

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
            "city" to listOf(SlotPromptAnnotation(simpleTemplates( { """city?""" })))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:WeatherConsult? = this@WeatherConsult
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::city}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> WeatherConsult_0(this)
        }
    }
}

data class WeatherConsult_0(
        val frame: WeatherConsult
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({"""Hi, city = ${frame.city} weather = ${frame.weather()}"""})) })

data class BoolGateTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var city: City? = null
    var placeHolder: String? = null


    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "city" to listOf(SlotPromptAnnotation(simpleTemplates( { """city?""" })),
                BoolGateAsk { SlotRequest("city", "io.opencui.test.City", simpleTemplates({ """do you need to specify a city?""" })) }),
        "placeHolder" to listOf(SlotPromptAnnotation(simpleTemplates( { """placeHolder?""" })))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:BoolGateTestIntent? = this@BoolGateTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::city}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::placeHolder}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> BoolGateTestIntent_0(this)
        }
    }
}

data class BoolGateTestIntent_0(
        val frame: BoolGateTestIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({"""Hi, city = ${frame.city} placeHolder = ${frame.placeHolder}""" })) })

data class NeverAskIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var city: City? = null
    var placeHolder: String? = null

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "city" to listOf(SlotPromptAnnotation(simpleTemplates( { """city?""" }))),
        "placeHolder" to listOf(NeverAsk(), SlotInitAnnotation(FillActionBySlot({createPlaceHolder()}, this, "placeHolder")))
    )

    fun createPlaceHolder(): String {
        return "associated place holder"
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:NeverAskIntent? = this@NeverAskIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::city}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::placeHolder}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> NeverAskIntent_0(this)
        }
    }
}

data class NeverAskIntent_0(
        val frame: NeverAskIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, city = ${frame.city} placeHolder = ${frame.placeHolder}""" }})) })

data class ZepTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var citySoft: City? = null

    var cityHard: City? = null

    var citiesSoft: MutableList<City>? = null

    var citiesHard: MutableList<City>? = null

    @JsonIgnore
    fun zeroEntry(city: City?): List<City> {
        return if (city != null) listOf(city) else listOf()
    }

    @JsonIgnore
    fun zeroEntryForHard(city: City?): List<City> {
        if (city != null) return listOf(city)
        return if (citySoft?.value == "Beijing") listOf(City("Shanghai"), City("Shenzhen")) else listOf()
    }

    @JsonIgnore
    fun zeroEntryForMultiValueHard(city: City?): List<City> {
        if (city != null) return listOf(city)
        return if (cityHard?.value == "Shanghai" || (citiesHard != null && citiesHard!!.size > 0)) listOf() else listOf(City("Shenzhen"), City("Chengdu"))
    }

    @JsonIgnore
    val _rec_citySoft = {it: City? -> PagedSelectable<City>(
        session, {zeroEntry(it)}, { City::class },
            {offers -> SlotOffer(offers, "citySoft", "io.opencui.test.City",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "citySoft", hard = false,
        zeroEntryActions = listOf(TextOutputAction { SlotOfferZepInform("citySoft", "io.opencui.test.City", simpleTemplates({ """zero entry for citySoft""" })) }))}

    @JsonIgnore
    val _rec_cityHard = {it: City? -> PagedSelectable<City>(
        session, {zeroEntryForHard(it)}, { City::class },
            {offers -> SlotOffer(offers, "cityHard", "io.opencui.test.City",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "cityHard", hard = true,
        zeroEntryActions = listOf(
            TextOutputAction { SlotOfferZepInform("cityHard", "io.opencui.test.City", simpleTemplates({ """zero entry for cityHard""" })) }, AbortIntentAction(AbortIntent(session))))}

    @JsonIgnore
    val _rec_citiesSoft = {it: City? -> PagedSelectable<City>(
        session, {zeroEntry(it)}, { City::class },
            {offers -> SlotOffer(offers, "citiesSoft", "kotlin.collections.List<io.opencui.test.City>",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "citiesSoft", hard = false,
        zeroEntryActions = listOf(TextOutputAction({ SlotOfferZepInform("citiesSoft", "kotlin.collections.List<io.opencui.test.City>", simpleTemplates( {"""zero entry for citiesSoft"""})) })))}

    @JsonIgnore
    val _rec_citiesHard = {it: City? -> PagedSelectable<City>(
        session, {zeroEntryForMultiValueHard(it)}, { City::class },
            {offers -> SlotOffer(offers, "citiesHard", "kotlin.collections.List<io.opencui.test.City>",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "citiesHard", hard = true,
        zeroEntryActions = listOf(TextOutputAction({ SlotOfferZepInform("citiesHard", "kotlin.collections.List<io.opencui.test.City>", simpleTemplates( {"""zero entry for citiesHard"""})) }),
                LazyPickAction { if (citiesHard != null && citiesHard!!.size >= 1) EndSlot(this, "citiesHard", true) else AbortIntentAction(AbortIntent(session)) }))}

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "citySoft" to listOf(SlotPromptAnnotation(simpleTemplates( { """citySoft?""" })), TypedValueRecAnnotation<City>({_rec_citySoft(this)})),
        "cityHard" to listOf(SlotPromptAnnotation(simpleTemplates( { """cityHard?""" })), TypedValueRecAnnotation<City>({_rec_cityHard(this)})),
        "citiesSoft" to listOf(SlotConditionalPromptAnnotation({if (citiesSoft!!.isEmpty()) simpleTemplates( { "citiesSoft?" })
        else simpleTemplates( { "any citiesSoft else?" })}),
                MinMaxAnnotation(1,
                        { SlotNotifyFailure(citiesSoft, "citiesSoft", "kotlin.collections.List<io.opencui.test.City>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${citiesSoft!!.size} less than 1" })) },
                        2,
                        { SlotNotifyFailure(citiesSoft, "citiesSoft", "kotlin.collections.List<io.opencui.test.City>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${citiesSoft!!.size} greater than 2" })) }
                ),
            TypedValueRecAnnotation<City>({_rec_citiesSoft(this)})),
        "citiesHard" to listOf(SlotConditionalPromptAnnotation({if (citiesHard!!.isEmpty()) simpleTemplates( { "citiesHard?" })
        else simpleTemplates( { "any citiesHard else?" })}),
                MinMaxAnnotation(1,
                        { SlotNotifyFailure(citiesHard, "citiesHard", "kotlin.collections.List<io.opencui.test.City>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${citiesHard!!.size} less than 1" })) },
                        3,
                        { SlotNotifyFailure(citiesHard, "citiesHard", "kotlin.collections.List<io.opencui.test.City>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${citiesHard!!.size} greater than 2" })) }
                ),
            TypedValueRecAnnotation<City>({_rec_citiesHard(this)}))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ZepTestIntent? = this@ZepTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::citySoft}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::cityHard}) { s -> Json.decodeFromString(s) })
                addWithPath(MultiValueFiller(
                    { frame!!::citiesSoft },
                    fun(p: KMutableProperty0<City?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
                addWithPath(MultiValueFiller(
                    { frame!!::citiesHard },
                    fun(p: KMutableProperty0<City?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ZepTestIntent_0(this)
        }
    }
}

data class ZepTestIntent_0(
        val frame: ZepTestIntent
) : TextOutputAction({
    UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, 
                                            |citySoft = ${citySoft} 
                                            |cityHard = ${cityHard} 
                                            |citiesSoft = ${citiesSoft?.joinToString { it.value }} 
                                            |citiesHard = ${citiesHard?.joinToString { it.value }}""".trimMargin() }}
))
})

data class SlotUpdate<T: Any>(override var session: UserSession? = null): AbstractSlotUpdate<T>(session) {
    override val informNewValuePrompt = { SlotInform(newValue, "newValue", "", simpleTemplates(with(session!!){"""we have updated ${if (!isMV()) originalSlot!!.name() else "the ${index!!.name()} ${originalSlot!!.name()}"} form ${originalValue()!!.name()} to ${newValue!!.name()} for you"""})) }
    override val askNewValuePrompt = { SlotRequest("newValue", "", simpleTemplates( {with(session!!){"""What do you want for ${if (!isMV()) originalSlot!!.name() else "${index!!.name()} ${originalSlot!!.name()}"}?"""}})) }
    override val oldValueDisagreePrompt = { SlotConfirm(oldValue, "oldValue", "", simpleTemplates(with(session!!){"You just said ${oldValue!!.name()}, do you mean you want to change ${if (isMV()) "${index!!.name()} " else ""}${originalSlot!!.name()} from ${originalValue()!!.name()}?"})) }
    override val doNothingPrompt = { SlotOfferZepInform("originalSlot", "", simpleTemplates( { "We have no clue what you are talking about." })) }
    override val askIndexPrompt = { SlotRequest("index", "", simpleTemplates( {with(session!!){"There are multiple values in ${originalSlot!!.name()}. Which one do you want to change?"}})) }
    override val wrongIndexPrompt = { SlotNotifyFailure(index, "index", "", FailType.VC, simpleTemplates(StaticPrompt(with(session!!){"""There's no ${index!!.name()} value in ${originalSlot!!.name()}"""}))) }
    override val indexRecPrompt: (List<Ordinal>) -> ComponentDialogAct = { offers -> SlotOffer(offers, "index", "", simpleTemplates(listOf(LazyEvalPrompt { offers.withIndex().joinToString("\n") { with(session!!) {"${it.index + 1}. ${it.value.name()} value: ${getValueByIndex(it.value)?.name()}" }} }))) }
}


data class SlotUpdateTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var cityFrom: City? = null

    var cityTo: City? = null

    var citiesFrom: MutableList<City>? = null

    var citiesTo: MutableList<City>? = null

    @JsonIgnore
    fun recs(candidate: City?): List<City> {
        return if (candidate != null) listOf(candidate) else listOf(City("Beijing"), City("Shanghai"))
    }

    @JsonIgnore
    val _rec_cityFrom = {it: City? -> PagedSelectable<City>(
        session, {recs(it)}, { City::class },
            {offers -> SlotOffer(offers, "cityFrom", "io.opencui.test.City",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session!!){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it.name()})" }}."""}})))
            },
        pageSize = 5, target = this, slot = "cityFrom", hard = true)}

    @JsonIgnore
    override val annotations: Map<kotlin.String, List<Annotation>> = mapOf(
        "cityFrom" to listOf(SlotPromptAnnotation(simpleTemplates( { """cityFrom?""" })), TypedValueRecAnnotation<City>({_rec_cityFrom(this)})),
        "cityTo" to listOf(SlotPromptAnnotation(simpleTemplates({"""cityFrom is ${cityFrom}, cityTo?"""}))),
        "citiesFrom" to listOf(SlotConditionalPromptAnnotation({if (citiesFrom!!.isEmpty()) simpleTemplates( { "citiesFrom?" }) else simpleTemplates( { "any citiesFrom else?" })}),
                MinMaxAnnotation(0,
                        { SlotNotifyFailure(citiesFrom, "citiesFrom", "kotlin.collections.List<io.opencui.test.City>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${citiesFrom!!.size} less than 0" })) },
                        2,
                        { SlotNotifyFailure(citiesFrom, "citiesFrom", "kotlin.collections.List<io.opencui.test.City>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${citiesFrom!!.size} greater than 2" })) })
        ),
        "citiesTo" to listOf(
                SlotConditionalPromptAnnotation({if (citiesTo!!.isEmpty()) simpleTemplates( { "citiesTo?" }) else simpleTemplates( { "any citiesTo else?" })}),
                MinMaxAnnotation(0,
                        { SlotNotifyFailure(citiesTo, "citiesTo", "kotlin.collections.List<io.opencui.test.City>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${citiesTo!!.size} less than 0" })) },
                        3,
                        { SlotNotifyFailure(citiesTo, "citiesTo", "kotlin.collections.List<io.opencui.test.City>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${citiesTo!!.size} greater than 2" })) })
        )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:SlotUpdateTestIntent? = this@SlotUpdateTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::cityFrom}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::cityTo}) { s -> Json.decodeFromString(s) })
                addWithPath(MultiValueFiller(
                    { frame!!::citiesFrom },
                    fun(p: KMutableProperty0<City?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
                addWithPath(MultiValueFiller(
                    { frame!!::citiesTo },
                    fun(p: KMutableProperty0<City?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> SlotUpdateTestIntent_0(this)
        }
    }
}

data class SlotUpdateTestIntent_0(
        val frame: SlotUpdateTestIntent
) : TextOutputAction({
    UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, 
                                            |cityFrom = ${cityFrom} 
                                            |cityTo = ${cityTo} 
                                            |citiesFrom = ${citiesFrom?.joinToString { it.value }} 
                                            |citiesTo = ${citiesTo?.joinToString { it.value }}""".trimMargin() }}
))
})

data class EarlyTerminationFrame(override var session: UserSession? = null): IFrame {
    @JsonIgnore
    override val type = FrameKind.FRAME

    var a: String? = null
    var b: String? = null

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( { """a?""" }))),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( { """b?""" }))))

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:EarlyTerminationFrame? = this@EarlyTerminationFrame
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString<String>(s) })
            filler.addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString<String>(s) })
            return filler
        }
    }
}


data class EarlyTerminationIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    fun earlyTerminationCondition():Boolean {
        return f?.a == "aaa"
    }

    var f: EarlyTerminationFrame? = EarlyTerminationFrame(session)

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "f.a" to listOf(
            SlotDoneAnnotation({earlyTerminationCondition()}, listOf(
                EndSlot(this, null, true),
                TextOutputAction({ UserDefinedInform(this, simpleTemplates({"""we don't have choices that meet your requirements, intent terminated""" })) }))
            )
        )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:EarlyTerminationIntent? = this@EarlyTerminationIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.f!!.createBuilder().invoke(path.join("f", f)))
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            earlyTerminationCondition() -> EarlyTerminationIntent_1(this)
            else -> EarlyTerminationIntent_0(this)
        }
    }
}

data class EarlyTerminationIntent_0(
        val frame: EarlyTerminationIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, a = ${f?.a}""" }})) })

data class EarlyTerminationIntent_1(
        val frame: EarlyTerminationIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""early terminated response, should not appear""" }})) })

data class ReturnValueTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var a: Int? = null
    var b: Int? = null
    var result: MutableList<String>? = null

    fun recB(): List<Int> {
        return listOf(1, 2)
    }

    @JsonIgnore
    val recommendation = PagedSelectable<Int>(
        session, {recB()}, { Int::class },
            {offers -> SlotOffer(offers, "b", "kotlin.Int",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "b")

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( { "a?" }))),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( { "b?" })), ValueRecAnnotation({recommendation})),
        "result" to listOf(NeverAsk(), SlotInitAnnotation(DirectlyFillActionBySlot({initResult()}, this, "result")))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ReturnValueTestIntent? = this@ReturnValueTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString<Int>(s) })
            filler.addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString<Int>(s) })
            filler.addWithPath(MultiValueFiller(
                { frame!!::result },
                fun(p: KMutableProperty0<String?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            return filler
        }
    }

    fun initResult(): List<String> {
        return if (a != 1) listOf("aaa", "bbb") else listOf("ccc", "ddd")
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ReturnValueTestIntent_0(this)
        }
    }
}

data class ReturnValueTestIntent_0(
    val frame: ReturnValueTestIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, return value test response""" }})) })


// TODO(xiaobo): the correct behavior should be asking s? first then start the recommendation?
// assuming the recommendation is on s. But it does not seems to be the case.
data class ValueRecommendationTest(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var s: String? = null

    @JsonIgnore
    val recommendation = PagedSelectable<String>(
        session, JsonFrameBuilder("""{"@class": "io.opencui.test.ReturnValueTestIntent", "a": 1}""", listOf(session)),
        { String::class },
            {offers -> SlotOffer(offers, "s", "kotlin.String",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "s")

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "s" to listOf(
            SlotPromptAnnotation(simpleTemplates( { "s?" })),
            ValueRecAnnotation({recommendation}, false)
        )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ValueRecommendationTest? = this@ValueRecommendationTest
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString<String>(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ValueRecommendationTest_0(this)
        }
    }
}

data class ValueRecommendationTest_0(
        val frame: ValueRecommendationTest
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, value recommendation test response s = $s""" }})) })

data class DirectlyFillMultiValueSlotTest(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var payMethodList: MutableList<PayMethod>? = mutableListOf()
    var s: String? = null
    var payMethodListCopy: MutableList<PayMethod>? = mutableListOf()

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "s" to listOf(
            SlotPromptAnnotation(simpleTemplates( { "s?" })),
            SlotDoneAnnotation({s == "aaa"}, listOf(DirectlyFillActionBySlot({payMethodList}, this, "payMethodListCopy")))
        ),
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation({if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" }) else simpleTemplates( { "anything else?" })})),
        "payMethodListCopy" to listOf(
            SlotConditionalPromptAnnotation({if (payMethodListCopy!!.isEmpty()) simpleTemplates( { "payMethod copy?" }) else simpleTemplates( { "any payMethod copy else?" })}))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:DirectlyFillMultiValueSlotTest? = this@DirectlyFillMultiValueSlotTest
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString<String>(s) })
            filler.addWithPath(MultiValueFiller(
                { frame!!::payMethodListCopy },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> DirectlyFillMultiValueSlotTest_0(this)
        }
    }
}

data class DirectlyFillMultiValueSlotTest_0(
    val frame: DirectlyFillMultiValueSlotTest
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, value recommendation test response s = $s payMethodListCopy : ${payMethodListCopy?.joinToString { it.value }}""" }})) })

data class MVEntryConfirmationTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var payMethodList: MutableList<PayMethod>? = mutableListOf()
    var s: String? = null

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "s" to listOf(
            SlotPromptAnnotation(simpleTemplates( { "s?" }))
        ),
        "payMethodList" to listOf(
            SlotConditionalPromptAnnotation({
                if (payMethodList!!.isEmpty()) simpleTemplates( { "payMethod?" })
                else simpleTemplates( { "anything else?" })})
        ),
        "payMethodList._item" to listOf(ConfirmationAnnotation({searchConfirmation("payMethodList._item")}))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:MVEntryConfirmationTestIntent? = this@MVEntryConfirmationTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString<String>(s) })
            filler.addWithPath(MultiValueFiller(
                { frame!!::payMethodList },
                fun(p: KMutableProperty0<PayMethod?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } }))
            return filler
        }
    }

    val mvEntryConfirm = Confirmation(session, null, "",
            { SlotInform<PayMethod>(null, "payMethodList._item", "io.opencui.test.PayMethod", simpleTemplates( { "you chose ${payMethodList?.last()?.value}" })) },
            true)

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "payMethodList._item" -> {
                when (s) {
                    "aaa" -> mvEntryConfirm
                    else -> null
                }
            }
            else -> null
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> MVEntryConfirmationTestIntent_0(this)
        }
    }
}

data class MVEntryConfirmationTestIntent_0(
        val frame: MVEntryConfirmationTestIntent
) : TextOutputAction({
    UserDefinedInform(frame,
        simpleTemplates({with(frame) {"""Hi, value recommendation test response s = $s payMethodList = ${payMethodList?.joinToString { it.value }}""" }}))
})

data class VCTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var a: Int? = null
    var b: String? = null
    var c: Boolean? = null
    var d: String? = null

    fun checkAB(): Boolean {
        return a != 1 || b != "c"
    }

    fun checkABC(): Boolean {
        return a != 1 || b != "a" || c != true
    }

    @JsonIgnore
    public var _check_ab: OldValueCheck = OldValueCheck(session, {checkAB()}, listOf(Pair(this, "b")),
            { SlotNotifyFailure(b, "b", "kotlin.String", FailType.VC, simpleTemplates(LazyEvalPrompt { "b fails" })) }
    )
    @JsonIgnore
    public var _check_abc: ValueCheck = ValueCheck(session, {checkABC()},
        listOf(
            TextOutputAction(
                    { SlotNotifyFailure(c, "c", "kotlin.Boolean", FailType.VC, simpleTemplates(LazyEvalPrompt { """a, b and c fail""" })) }
            ),
            CleanupActionBySlot(listOf(Pair(this, "a"), Pair(this, "b"), Pair(this, "c"))),
            RefocusActionBySlot(this, "a")
        )
    )

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
            "a" to listOf(
                    SlotPromptAnnotation(simpleTemplates( { "a?" }))
            ),
            "b" to listOf(
                    SlotPromptAnnotation(simpleTemplates( { "b?" })), ValueCheckAnnotation(_check_ab)
            ),
            "c" to listOf(
                    SlotPromptAnnotation(simpleTemplates( { "c?" })), ValueCheckAnnotation(_check_abc)
            ),
            "d" to listOf(
                    SlotPromptAnnotation(simpleTemplates( { "d?" }))
            )
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:VCTestIntent? = this@VCTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString<Int>(s) })
            filler.addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString<String>(s) })
            filler.addWithPath(EntityFiller({frame!!::c}) { s -> Json.decodeFromString<Boolean>(s) })
            filler.addWithPath(EntityFiller({frame!!::d}) { s -> Json.decodeFromString<String>(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> VCTestIntent_0(this)
        }
    }
}

data class VCTestIntent_0(
        val frame: VCTestIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, value check test response a = $a b = $b c = $c""" }})) })

data class ValueRecheckTestIntent(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var a: Int? = null
    var b: String? = null
    var c: Boolean? = null
    var d: String? = null

    fun checkAB(): Boolean {
        return a == 1 || b == "a"
    }

    fun checkAC(): Boolean {
        return a != 1 || c != true
    }

    @JsonIgnore
    public var _check_ab: ValueCheck = ValueCheck(session, {checkAB()}, listOf(
        TextOutputAction({ SlotNotifyFailure(b, "b", "kotlin.String", FailType.VC, simpleTemplates(LazyEvalPrompt { """b fails""" })) }),
        CleanupActionBySlot(listOf(Pair(this, "b")))
    ))
    @JsonIgnore
    public var _check_ac: ValueCheck = ValueCheck(session, {checkAC()}, listOf(
        TextOutputAction({ SlotNotifyFailure(c, "c", "kotlin.Boolean", FailType.VC, simpleTemplates(LazyEvalPrompt { """a and c fails""" })) }),
        CleanupActionBySlot(listOf(Pair(this, "a"))),
        CleanupActionBySlot(listOf(Pair(this, "c"))),
        RecheckActionBySlot(listOf(Pair(this, "b"))),
        RefocusActionBySlot(this, "a")
    ))

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "a" to listOf(SlotPromptAnnotation(simpleTemplates( { "a?" }))),
        "b" to listOf(SlotPromptAnnotation(simpleTemplates( { "b?" })), ValueCheckAnnotation(_check_ab)),
        "c" to listOf(SlotPromptAnnotation(simpleTemplates( { "c?" })), ValueCheckAnnotation(_check_ac)),
        "d" to listOf(SlotPromptAnnotation(simpleTemplates( { "d?" })))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:ValueRecheckTestIntent? = this@ValueRecheckTestIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::a}) { s -> Json.decodeFromString<Int>(s) })
            filler.addWithPath(EntityFiller({frame!!::b}) { s -> Json.decodeFromString<String>(s) })
            filler.addWithPath(EntityFiller({frame!!::c}) { s -> Json.decodeFromString<Boolean>(s) })
            filler.addWithPath(EntityFiller({frame!!::d}) { s -> Json.decodeFromString<String>(s) })
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> ValueRecheckTestIntent_0(this)
        }
    }
}

data class ValueRecheckTestIntent_0(
        val frame: ValueRecheckTestIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, value check test response a = $a b = $b c = $c""" }})) })

data class UserInit(override var session: UserSession? = null): IKernelIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var cellPhone: String? = null
    var userName: String? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "Greeting" to listOf(),
        "cellPhone" to listOf(
            SlotPromptAnnotation(simpleTemplates( { "What is your cell number?" })),),
        "userName" to listOf(
            SlotPromptAnnotation(simpleTemplates( { "What is your name?" })),))

    override fun searchResponse(): Action? = when {
        else -> null
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: UserInit? = this@UserInit

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::cellPhone}) { s -> Json.decodeFromString(s) })
            filler.addWithPath(EntityFiller({frame!!::userName}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class MainWithKernelIntent(
        override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var Greeting: Greeting? = Greeting(session)

    var user: UserInit? = UserInit(session)

    @JsonIgnore
    var skills: MutableList<IIntent>? = null

    var Goodbye: Goodbye? = Goodbye(session)

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("Greeting" to listOf(),
            "user" to listOf(),
            "skills" to listOf(
                    SlotConditionalPromptAnnotation({if (skills!!.isEmpty()) simpleTemplates( {"""What can I do for you? (MainWithKernelIntent)"""}) else simpleTemplates( { """What else can I do for you? (MainWithKernelIntent)""" })})
            ),
            "Goodbye" to listOf())

    override fun searchResponse(): Action? = when {
        else -> null
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: MainWithKernelIntent? = this@MainWithKernelIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.Greeting!!.createBuilder().invoke(path.join("Greeting", Greeting)))
            filler.add(frame!!.user!!.createBuilder().invoke(path.join("user", user)))
            filler.addWithPath(MultiValueFiller(
                { frame!!::skills },
                fun(p: KMutableProperty0<IIntent?>): ICompositeFiller {
                    return InterfaceFiller({ p }, createFrameGenerator(frame!!.session!!, "io.opencui.core.IIntent"))}))
            filler.add(frame!!.Goodbye!!.createBuilder().invoke(path.join( "Goodbye", Goodbye)))
            return filler
        }
    }
}

data class ValueRecOutlierValueIntent(
        override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    fun recData(): List<String> {
        return listOf("a", "b", "c")
    }

    @JsonIgnore
    var s: String? = null

    val _rec_s = PagedSelectable<String>(
        session, {recData()}, { String::class },
            {offers -> SlotOffer(offers, "s", "kotlin.String",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 5, target = this, slot = "s", hard = true,
        valueOutlierPrompt = { SlotOfferOutlier(it, "s", "kotlin.String", simpleTemplates(LazyEvalPrompt { with(it) {with(session!!){"""outlier value: ${value?.name()}"""}}})) },
        indexOutlierPrompt = { SlotOfferOutlier(it, "s", "kotlin.String", simpleTemplates(LazyEvalPrompt { with(it) { with(session!!) {"""outlier index : ${index}""" }}})) })

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("s" to listOf(
            SlotPromptAnnotation(simpleTemplates( { """s?""" })),
            ValueRecAnnotation({_rec_s})
    ))

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""s=${s}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: ValueRecOutlierValueIntent? = this@ValueRecOutlierValueIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class TestSepNoIntent(
        override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    fun recData(): List<String> {
        return listOf("a")
    }

    @JsonIgnore
    var s: String? = null

    @JsonIgnore
    var ss: MutableList<String>? = null

    val _rec_s = PagedSelectable<String>(
        session, {recData()}, { String::class },
            {offers -> SlotOffer(offers, "s", "kotlin.String",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 5, target = this, slot = "s", hard = true,
        zeroEntryActions = listOf(TextOutputAction(
                { SlotOfferZepInform("s", "kotlin.String", simpleTemplates( { """zero entry for s""" })) }
        ), AbortIntentAction(AbortIntent(session))),
        singleEntryPrompt = { SlotOfferSepInform(it, "s", "kotlin.String", simpleTemplates(LazyEvalPrompt {"""only ${it} left for s, would u like it?"""})) },
        implicit = false, autoFillSwitch = {true})

    val _rec_ss = PagedSelectable<String>(
        session, {recData()}, { String::class },
            {offers -> SlotOffer(offers, "ss", "kotlin.collections.List<kotlin.String>",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                        "(${it})" }}."""}})))
            },
        pageSize = 5, target = this, slot = "ss", hard = true,
        zeroEntryActions = listOf(TextOutputAction(
                { SlotOfferZepInform("ss", "kotlin.collections.List<kotlin.String>", simpleTemplates( { """zero entry for ss""" })) }
        ), LazyPickAction({if (ss != null && ss!!.size >= 1) EndSlot(this, "ss", true) else AbortIntentAction(AbortIntent(session))})),
        singleEntryPrompt = { SlotOfferSepInform(it, "ss", "kotlin.collections.List<kotlin.String>", simpleTemplates(LazyEvalPrompt {"""only ${it} left for ss, would u like it?"""})) },
        implicit = false, autoFillSwitch = {true}) // explicit confirmation always takes effect

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
            "s" to listOf(
                SlotPromptAnnotation(simpleTemplates( { """s?""" })),
                ValueRecAnnotation({_rec_s})
            ),
            "ss" to listOf(
                SlotConditionalPromptAnnotation {
                    if (ss == null || ss!!.isEmpty()) simpleTemplates( { """ss?""" })
                    else simpleTemplates( { """else ss?""" }) },
                MinMaxAnnotation(1,
                        { SlotNotifyFailure(ss, "ss", "kotlin.collections.List<kotlin.String>", FailType.MIN, simpleTemplates(LazyEvalPrompt { "size = ${ss!!.size} less than 1" })) },
                        3,
                        { SlotNotifyFailure(ss, "ss", "kotlin.collections.List<kotlin.String>", FailType.MAX, simpleTemplates(LazyEvalPrompt { "size = ${ss!!.size} greater than 3" })) }
                ),
                ValueRecAnnotation({_rec_ss})
            )
    )

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""s=${s}; ss=${ss?.joinToString { it }}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: TestSepNoIntent? = this@TestSepNoIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString(s) })
            val msfiller = MultiValueFiller(
                { frame!!::ss },
                fun(p: KMutableProperty0<String?>): AEntityFiller { return EntityFiller({p}) { s -> Json.decodeFromString(s) } })
            filler.addWithPath(msfiller)
            return filler
        }
    }
}

data class FreeActionConfirmationTestIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    var s: String? = null

    @JsonIgnore
    var confirmS: FreeActionConfirmation = FreeActionConfirmation(session,
            { SlotInform(s, "s", "kotlin.String", simpleTemplates({"""r u sure of string value $s"""})) },
            { SlotInform(s, "s", "kotlin.String", simpleTemplates({"""What do you want to do next? You can change your choice before, leave the task and more."""})) })

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("s" to listOf(
        SlotPromptAnnotation(simpleTemplates( { """s?""" })),
        ConfirmationAnnotation({searchConfirmation("s")})
    ))

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "s" -> confirmS
            else -> null
        }
    }

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction { UserDefinedInform(this, simpleTemplates({ """s=${s}""" })) }
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: FreeActionConfirmationTestIntent? = this@FreeActionConfirmationTestIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class SimpleIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    var s: String? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("s" to listOf(SlotPromptAnnotation(simpleTemplates( { """s?""" }))))

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""s=${s}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: SimpleIntent? = this@SimpleIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class MobileWithAdvancesForMapping(@JsonInclude(JsonInclude.Include.NON_NULL) override var session: UserSession? = null
) : IFrame {
    var nameMapping: String? = null
    var cellphoneMapping: String? = null
    var id: Int? = null

    @JsonIgnore
    override val type = FrameKind.FRAME

    @JsonIgnore
    override val annotations = mapOf<kotlin.String, List<Annotation>>(
            "id" to listOf(),
            "cellphoneMapping" to listOf(),
            "nameMapping" to listOf()
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object :  FillBuilder {
        var frame: MobileWithAdvancesForMapping? = this@MobileWithAdvancesForMapping

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({frame!!::nameMapping}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::cellphoneMapping}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({frame!!::id}) { s -> Json.decodeFromString<Int>(s) })
            }
            return filler
        }
    }
}

data class ExternalEventContainerIntent(
        override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var intent: ExternalEventIntent? = ExternalEventIntent(session)

    var result: ExternalEventIntent? = ExternalEventIntent(session)

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "intent" to listOf(
            SlotInformActionAnnotation(
                listOf(TextOutputAction({ SlotInform(intent, "intent", "io.opencui.test.ExternalEventIntent", simpleTemplates( { """we are waiting for callback...""" })) }))
            ),
            ExternalEventStrategy()),
        "result" to listOf(
            SlotInformActionAnnotation(
                listOf(TextOutputAction({ SlotInform(result, "result", "io.opencui.test.ExternalEventIntent", simpleTemplates( { """we are waiting for callback for async result...""" })) }))
            ),
            ExternalEventStrategy()),
        "this" to listOf(ConfirmationAnnotation({searchConfirmation("this")}))
    )

    @JsonIgnore
    var confirmThis: FreeActionConfirmation = FreeActionConfirmation(session,
            { SlotInform(this, "this", "io.opencui.test.ExternalEventContainerIntent", simpleTemplates({"""implicitly confirm this intent.s=${intent?.s}"""})) },
            { SlotInform(this, "this", "io.opencui.test.ExternalEventContainerIntent", simpleTemplates({"""What do you want to do next? You can change your choice before, leave the task and more."""})) },
            true)

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "this" -> confirmThis
            else -> null
        }
    }

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""intent=${intent?.s}; result=${result?.s}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: ExternalEventContainerIntent? = this@ExternalEventContainerIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.intent!!.createBuilder().invoke(path.join("intent", intent)))
            filler.add(frame!!.result!!.createBuilder().invoke(path.join("result", result)))
            return filler
        }
    }
}

data class ExternalEventIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    var s: String? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("s" to listOf(
        SlotPromptAnnotation(simpleTemplates( { """s?""" })),
        NeverAsk())
    )

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""s=${s}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: ExternalEventIntent? = this@ExternalEventIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::s}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class ContextBasedRecFrame(
    override var session: UserSession? = null
) : IFrame {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var a: String? = null
    var b: String? = null

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("a" to listOf(
        SlotPromptAnnotation(simpleTemplates( { """ContextBasedRecFrame a?""" })),
        AlwaysAsk()),
        "b" to listOf(
            SlotPromptAnnotation(simpleTemplates( { """ContextBasedRecFrame b?""" })),
            AlwaysAsk())
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: ContextBasedRecFrame? = this@ContextBasedRecFrame
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val tp = p as? KMutableProperty0<ContextBasedRecFrame?> ?: ::frame
            val filler = FrameFiller({ tp }, path)
            filler.addWithPath(EntityFiller({tp.get()!!::a}) { s -> Json.decodeFromString(s) })
            filler.addWithPath(EntityFiller({tp.get()!!::b}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}

data class RecommendationIntentForContextBasedRec(override var session: UserSession? = null): IIntent {
    @JsonIgnore
    override val type = FrameKind.BIGINTENT

    var rf: ContextBasedRecFrame? = ContextBasedRecFrame(session)
    var result: MutableList<ContextBasedRecFrame>? = null

    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = mapOf(
        "rf" to listOf(),
        "result" to listOf(NeverAsk(), SlotInitAnnotation(DirectlyFillActionBySlot({initResult()}, this, "result")))
       )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:RecommendationIntentForContextBasedRec? = this@RecommendationIntentForContextBasedRec
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.rf!!.createBuilder(frame!!::rf).invoke(path.join("rf", rf)))
            filler.addWithPath(MultiValueFiller(
                { frame!!::result },
                fun(p: KMutableProperty0<ContextBasedRecFrame?>): ICompositeFiller {
                    val builder = p.apply { set(ContextBasedRecFrame(frame!!.session)) }.get()!!.createBuilder()
                    return builder.invoke(path.join("result._item", p.get()))
                }
            ))
            return filler
        }
    }

    fun initResult(): List<ContextBasedRecFrame> {
        return if (rf?.a != "a") {
            listOf(
                ContextBasedRecFrame().apply {
                    a = "a"
                    b = "a"
                },
                ContextBasedRecFrame().apply {
                    a = "b"
                    b = "b"
                })
        } else {
            listOf(
                ContextBasedRecFrame().apply {
                    a = "c"
                    b = "c"
                },
                ContextBasedRecFrame().apply {
                    a = "d"
                    b = "d"
                })
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""rf=${rf?.a}"""})) })
        }
    }
}

data class ContextBasedRecIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    var f: ContextBasedRecFrame? = ContextBasedRecFrame()

    @JsonIgnore
    val recommendation: (ContextBasedRecFrame?) -> PagedSelectable<ContextBasedRecFrame> = {PagedSelectable<ContextBasedRecFrame>(
        session, JsonFrameBuilder("""{"@class": "io.opencui.test.RecommendationIntentForContextBasedRec"}""",
            listOf(session), mapOf("rf" to {it})
        ),
        { ContextBasedRecFrame::class },
            {offers -> SlotOffer(offers, "f", "io.opencui.test.ContextBasedRecFrame",
                    simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") { "(${it.a};${it.b})" }}."""}})))
            },
        pageSize = 2, target = this, slot = "f")}

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "f" to listOf(TypedValueRecAnnotation<ContextBasedRecFrame>({recommendation(this)}))
    )

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates( {"""f=${f?.a}"""})) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: ContextBasedRecIntent? = this@ContextBasedRecIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.f!!.createBuilder().invoke(path.join("f", f)))
            return filler
        }
    }
}

data class SlotDoubleConfirmTestIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    @JsonIgnore
    var slot: String? = null

    @JsonIgnore
    val recommendation: (String?) -> PagedSelectable<String> = {PagedSelectable<String>(
        session, { listOf("a") },
        { String::class },
        {offers -> SlotOffer(offers, "slot", "kotlin.String",
            simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") { it }}."""}})))
        },
        pageSize = 2, target = this, slot = "slot", hard = true, singleEntryPrompt = { SlotOfferSepInform(it, "slot", "kotlin.String", listOf(this), simpleTemplates(LazyEvalPrompt {"we only have $it; we chose it for u"})) }, implicit = true)}

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf(
        "slot" to listOf(
            TypedValueRecAnnotation<String>({recommendation(this)}),
            ConfirmationAnnotation {
                searchConfirmation("slot")
            },
            DialogActCustomizationAnnotation("io.opencui.core.SlotOfferSepInformConfirm") {
                simpleTemplates(StaticPrompt(with(it as SlotOfferSepInformConfirm<*>) { """combined confirm $slotName $slotType $target""" }))
            }
        )
    )

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction { UserDefinedInform(this, simpleTemplates({ """f=$slot""" })) }
    }

    @JsonIgnore
    var confirmSlot: Confirmation = Confirmation(session, this, "slot",
        { SlotConfirm(slot, "slot", "kotlin.String", listOf(this), simpleTemplates(LazyEvalPrompt {"""r u sure of slot value $slot"""})) })

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "slot" -> confirmSlot
            else -> null
        }
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: SlotDoubleConfirmTestIntent? = this@SlotDoubleConfirmTestIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::slot}) { s -> Json.decodeFromString(s) })
            return filler
        }
    }
}


