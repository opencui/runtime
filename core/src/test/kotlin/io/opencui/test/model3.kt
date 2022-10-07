package io.opencui.test

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import io.opencui.core.*
import io.opencui.core.Annotation
import io.opencui.core.da.*
import io.opencui.serialization.InterfaceInternalEntitySerializer
import io.opencui.serialization.Json
import io.opencui.serialization.deserializeInternalEntity
import kotlin.reflect.KMutableProperty0

data class SlotOfferSepInformConfirmRule(val slot0: SlotOfferSepInform<*>, val slot1: SlotConfirm<*>):
    CompositeDialogAct {
    override var result: ComponentDialogAct = SlotOfferSepInformConfirm(slot1.target, slot1.slotName, slot1.slotType, slot1.context)
}

data class SoftEarlyTerminationIntent(override var session: UserSession? = null): IIntent {
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
                EndSlot(this, null, false),
                TextOutputAction({ UserDefinedInform(this, simpleTemplates({"""we don't have choices that meet your requirements, intent terminated""" })) }))
            )
        ),
        "this" to listOf(ConfirmationAnnotation({searchConfirmation("this")}))
    )

    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame:SoftEarlyTerminationIntent? = this@SoftEarlyTerminationIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.add(frame!!.f!!.createBuilder().invoke(path.join("f", f)))
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            earlyTerminationCondition() -> SoftEarlyTerminationIntent_1(this)
            else -> SoftEarlyTerminationIntent_0(this)
        }
    }

    @JsonIgnore
    var confirmThis: Confirmation = Confirmation(session, this, "",
        { SlotConfirm(this, "", "io.opencui.test.SoftEarlyTerminationIntent", listOf(this), simpleTemplates(LazyEvalPrompt {"""r u sure of this intent and f.a value ${f?.a}"""})) })

    override fun searchConfirmation(slot: String): IFrame? {
        return when (slot) {
            "this" -> confirmThis
            else -> null
        }
    }
}


data class SoftEarlyTerminationIntent_0(
    val frame: SoftEarlyTerminationIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""Hi, a = ${f?.a}""" }})) })

data class SoftEarlyTerminationIntent_1(
    val frame: SoftEarlyTerminationIntent
) : TextOutputAction({ UserDefinedInform(frame, simpleTemplates({with(frame) {"""soft early terminated response, should appear""" }})) })


@JsonSerialize(using = InterfaceInternalEntitySerializer::class)
public interface Dish : InternalEntity{
    public fun normalized(): String?

    public fun getChildren(): List<Dish>

    companion object {
        @JvmStatic
        @JsonCreator
        fun create(node: JsonNode): Dish {
            return deserializeInternalEntity(node, "io.opencui.test.VirtualDish") as Dish
        }
    }
}

public data class VirtualDish(
    @get:JsonIgnore
    public override var value: String
) : Dish {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? = value

    @JsonIgnore
    public override fun getChildren(): List<Dish> = when (value) {
        "io.opencui.test.Drink" -> VirtualDrink.getAllInstances()
        "io.opencui.test.MainDish" -> MainDish.getAllInstances()
        "io.opencui.test.SideDish" -> SideDish.getAllInstances()
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<Dish> =
            listOf(VirtualDish("io.opencui.test.Drink"),
                VirtualDish("io.opencui.test.MainDish"),
                VirtualDish("io.opencui.test.SideDish"))
    }
}

public interface Drink : Dish {
    public override fun normalized(): String?

    public override fun getChildren(): List<Drink>
}

public data class VirtualDrink(
    @get:JsonIgnore
    public override var value: String
) : Drink {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? = value

    @JsonIgnore
    public override fun getChildren(): List<Drink> = when (value) {
        "io.opencui.test.ALDrink" -> ALDrink.getAllInstances()
        "io.opencui.test.NALDrink" -> NALDrink.getAllInstances()
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<Drink> =
            listOf(VirtualDrink("io.opencui.test.ALDrink"),
                VirtualDrink("io.opencui.test.NALDrink"))
    }
}

public data class MainDish(
    @get:JsonIgnore
    public override var value: String
) : Dish {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? =
        Agent.duMeta.getEntityInstances(MainDish::class.qualifiedName!!)[toString()]?.firstOrNull()

    @JsonIgnore
    public override fun getChildren(): List<MainDish> = when (value) {
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<MainDish> =
            Agent.duMeta.getEntityInstances(MainDish::class.qualifiedName!!).map { MainDish(it.key) }
    }
}

public data class SideDish(
    @get:JsonIgnore
    public override var value: String
) : Dish {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? =
        Agent.duMeta.getEntityInstances(SideDish::class.qualifiedName!!)[toString()]?.firstOrNull()

    @JsonIgnore
    public override fun getChildren(): List<SideDish> = when (value) {
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<SideDish> =
            Agent.duMeta.getEntityInstances(SideDish::class.qualifiedName!!).map { SideDish(it.key) }
    }
}

public data class ALDrink(
    @get:JsonIgnore
    public override var value: String
) : Drink {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? =
        Agent.duMeta.getEntityInstances(ALDrink::class.qualifiedName!!)[toString()]?.firstOrNull()

    @JsonIgnore
    public override fun getChildren(): List<ALDrink> = when (value) {
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<ALDrink> =
            Agent.duMeta.getEntityInstances(ALDrink::class.qualifiedName!!).map { ALDrink(it.key) }
    }
}

public data class NALDrink(
    @get:JsonIgnore
    public override var value: String
) : Drink {
    public override var origValue: String? = null

    @JsonValue
    public override fun toString(): String = value

    @JsonIgnore
    public override fun normalized(): String? =
        Agent.duMeta.getEntityInstances(NALDrink::class.qualifiedName!!)[toString()]?.firstOrNull()

    @JsonIgnore
    public override fun getChildren(): List<NALDrink> = when (value) {
        else -> listOf()
    }

    public companion object {
        @JsonIgnore
        public val valueGood: ((String) -> Boolean)? = { true }

        @JsonIgnore
        public fun getAllInstances(): List<NALDrink> =
            Agent.duMeta.getEntityInstances(NALDrink::class.qualifiedName!!).map { NALDrink(it.key) }
    }
}

data class AbstractEntityIntent(
    override var session: UserSession? = null
) : IIntent {
    @JsonIgnore
    override val type: FrameKind = FrameKind.BIGINTENT

    var dish: Dish? = null

    @get:JsonIgnore
    val dishService: IDishService
        get() = session!!.getExtension<IDishService>() as IDishService

    @JsonIgnore
    val recommendation = PagedSelectable<Dish>(
        session, {dishService.recDish()}, { Dish::class },
        {offers -> SlotOffer(offers, "dish", "io.opencui.test.Dish",
            simpleTemplates(listOf(LazyEvalPrompt {with(session){"""We have following ${offers.size} choices: ${offers.joinToString(", ") {
                "(${it.value})" }}."""}})))
        },
        pageSize = 5, target = this, slot = "dish")

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf("dish" to listOf(
        SlotPromptAnnotation(listOf(TextOutputAction { SlotRequest("dish", "io.opencui.test.Dish", simpleTemplates("What would u like?")) })),
        ValueRecAnnotation({recommendation}, false)
    ))

    override fun searchResponse(): Action? = when {
        else -> TextOutputAction({ UserDefinedInform(this, simpleTemplates(LazyEvalPrompt { """abstract entity type is ${dish!!::class.qualifiedName}; value is ${dish?.value}""" } )) })
    }

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: AbstractEntityIntent? = this@AbstractEntityIntent

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(EntityFiller({frame!!::dish}, {s: String? -> dish?.origValue = s}) { s, t -> Json.decodeFromString(s, session!!.findKClass(t ?: "io.opencui.test.VirtualDish")!!) as? Dish })
            return filler
        }
    }
}
