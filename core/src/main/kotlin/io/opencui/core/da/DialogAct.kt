package io.opencui.core.da

import io.opencui.core.IFrame
import io.opencui.core.Templates
import io.opencui.core.defaultTemplate
import java.io.Serializable

// This interface represents the dialog act that bot about to take. We start from the list from schema guided
// dialog. Notice the dialog act from user side is absorbed by dialog understanding, so we do not have model
// these explicitly. These represent what bot want to express, not how they express it.
interface DialogAct: Serializable

interface ComponentDialogAct: DialogAct {
    var templates: Templates
}

interface SlotDialogAct: ComponentDialogAct {
    val slotName: String
    val slotType: String
    val context: List<IFrame>
}

interface FrameDialogAct: ComponentDialogAct {
    val frameType: String
}

interface CompositeDialogAct: DialogAct {
    var result: ComponentDialogAct
}

// SLOT DialogAct
data class SlotRequest(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    // this kind of constructor is just for convenience of testcase
    constructor(slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(slotName, slotType, context, templates)
}

data class SlotRequestMore(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(slotName, slotType, context, templates)
}

data class SlotGate(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(slotName, slotType, context, templates)
}

enum class FailType {
    VC,
    MIN,
    MAX,
}

data class SlotNotifyFailure<T>(
    val target: T,
    override val slotName: String,
    override val slotType: String,
    val failType: FailType,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(target: T, slotName: String, slotType: String, failType: FailType, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(target, slotName, slotType, failType, context, templates)
}

data class SlotConfirm<T>(
    val target: T?,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(target: T?, slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(target, slotName, slotType, context, templates)
}

data class SlotInform<T>(
    val target: T?,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(target: T?, slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(target, slotName, slotType, context, templates)
}

data class SlotOffer<T>(
    val value: List<T>,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()): SlotDialogAct {
    constructor(value: List<T>, slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

data class SlotOfferSepInform<T>(
    val value: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(value: T, slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

data class SlotOfferZepInform(
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(slotName, slotType, context, templates)
}

data class SlotOfferOutlier<T>(
    val value: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct {
    constructor(value: T, slotName: String, slotType: String, templates: Templates = defaultTemplate(), context: List<IFrame> = listOf()): this(value, slotName, slotType, context, templates)
}

// FRAME DialogAct
data class FrameConfirm<T>(
    val target: T?,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct

data class FrameInform<T>(
    val target: T?,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct

data class FrameOffer<T>(
    val value: List<T>,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()): FrameDialogAct

data class FrameOfferSepInform<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct

data class FrameOfferZepInform(
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct

data class FrameOfferOutlier<T>(
    val value: T,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct

data class UserDefinedInform<T>(
    val target: T,
    override val frameType: String,
    override var templates: Templates = defaultTemplate()) : FrameDialogAct {
    constructor(target: T, templates: Templates): this(target, target!!::class.qualifiedName!!, templates)
}

// SPECIAL Component DialogAct for Composite DialogAct
data class SlotOfferSepInformConfirm<T>(
    val target: T,
    override val slotName: String,
    override val slotType: String,
    override val context: List<IFrame> = listOf(),
    override var templates: Templates = defaultTemplate()) : SlotDialogAct

// used as a placeholder for DialogAct that is not configured yet; this kind of DialogAct should not be called at runtime
class DumbDialogAct : ComponentDialogAct {
    override var templates: Templates = TODO("Not yet implemented")
}