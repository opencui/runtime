package services.framely.smsSender

import com.fasterxml.jackson.`annotation`.JsonIgnore
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.framely.core.IChatbot
import io.framely.core.IService
import io.framely.core.PhoneNumber
import io.framely.core.RoutingInfo
import io.framely.core.da.CompositeDialogAct
import io.framely.du.BertStateTracker
import io.framely.du.DUMeta
import io.framely.du.DUSlotMeta
import io.framely.du.EntityType
import io.framely.du.LangPack
import io.framely.du.StateTracker
import io.framely.serialization.Json
import java.lang.Class
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.reflect.KClass

public data class Agent(
  public val user: String?
) : IChatbot() {
  public override val duMeta: DUMeta
    public get() = Agent.duMeta

  public override val stateTracker: StateTracker
    public get() = Agent.stateTracker

  public override val rewriteRules: MutableList<KClass<out CompositeDialogAct>> = mutableListOf()

  public override val routing: Map<String, RoutingInfo> = mapOf()
  init {
    rewriteRules += Class.forName("io.framely.core.da.SlotOfferSepInformConfirmRule").kotlin as
        KClass<out CompositeDialogAct>
  }

  public constructor() : this("")

  public companion object {
    public val duMeta: DUMeta = loadDUMetaDsl(struct, Agent::class.java.classLoader,
        "services.framely", "smsSender", "struct", "master", "271", "Asia/Shanghai")

    public val stateTracker: StateTracker = BertStateTracker(duMeta)
  }
}

public object struct : LangPack {
  public override val frames: List<ObjectNode> = listOf()

  public override val entityTypes: Map<String, EntityType> = mapOf("kotlin.Int" to
      entityType("kotlin.Int") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "kotlin.Float" to entityType("kotlin.Float") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "kotlin.String" to entityType("kotlin.String") {
        children(listOf())
      }
      ,
      "kotlin.Boolean" to entityType("kotlin.Boolean") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.Email" to entityType("io.framely.core.Email") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "io.framely.core.PhoneNumber" to entityType("io.framely.core.PhoneNumber") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "io.framely.core.Ordinal" to entityType("io.framely.core.Ordinal") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "io.framely.core.Currency" to entityType("io.framely.core.Currency") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "kotlin.Unit" to entityType("kotlin.Unit") {
        children(listOf())
      }
      ,
      "java.time.LocalDateTime" to entityType("java.time.LocalDateTime") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "java.time.Year" to entityType("java.time.Year") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "java.time.YearMonth" to entityType("java.time.YearMonth") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "java.time.LocalDate" to entityType("java.time.LocalDate") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "java.time.LocalTime" to entityType("java.time.LocalTime") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      ,
      "io.framely.core.FrameType" to entityType("io.framely.core.FrameType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "java.time.DayOfWeek" to entityType("java.time.DayOfWeek") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "java.time.ZoneId" to entityType("java.time.ZoneId") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "kotlin.Any" to entityType("kotlin.Any") {
        children(listOf())
      }
      ,
      "io.framely.core.EntityType" to entityType("io.framely.core.EntityType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.SlotType" to entityType("io.framely.core.SlotType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.PromptMode" to entityType("io.framely.core.PromptMode") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.Language" to entityType("io.framely.core.Language") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.Country" to entityType("io.framely.core.Country") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.FillState" to entityType("io.framely.core.FillState") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.framely.core.FailType" to entityType("io.framely.core.FailType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      )

  public override val frameSlotMetas: Map<String, List<DUSlotMeta>> =
      mapOf("io.framely.core.PagedSelectable" to listOf(
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.framely.core.Ordinal", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.IDonotGetIt" to listOf(
      ),
      "io.framely.core.IDonotKnowWhatToDo" to listOf(
      ),
      "io.framely.core.AbortIntent" to listOf(
      DUSlotMeta(label = "intentType", isMultiValue = false, type = "io.framely.core.FrameType",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "intent", isMultiValue = false, type = "io.framely.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.GetLiveAgent" to listOf(
      ),
      "io.framely.core.BadCandidate" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "io.framely.core.SlotType", isHead
          = false, triggers = listOf()),
      ),
      "io.framely.core.BadIndex" to listOf(
      DUSlotMeta(label = "index", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.ConfirmationNo" to listOf(
      ),
      "io.framely.core.ResumeIntent" to listOf(
      DUSlotMeta(label = "intent", isMultiValue = false, type = "io.framely.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.SlotUpdate" to listOf(
      DUSlotMeta(label = "originalSlot", isMultiValue = false, type = "io.framely.core.SlotType",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "oldValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.framely.core.Ordinal", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "newValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "confirm", isMultiValue = false, type =
          "io.framely.core.confirmation.IStatus", isHead = false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotRequest" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotRequestMore" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotNotifyFailure" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "failType", isMultiValue = false, type = "io.framely.core.FailType", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotOffer" to listOf(
      DUSlotMeta(label = "value", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotOfferSepInform" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotOfferZepInform" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.FrameInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.framely.core.da.SlotGate" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.FrameOffer" to listOf(
      DUSlotMeta(label = "value", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.da.FrameOfferSepInform" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.da.FrameOfferZepInform" to listOf(
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.da.FrameConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.framely.core.da.UserDefinedInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.framely.core.da.SlotOfferSepInformConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.framely.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.da.SlotOfferSepInformConfirmRule" to listOf(
      DUSlotMeta(label = "slot0", isMultiValue = false, type =
          "io.framely.core.da.SlotOfferSepInform", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "slot1", isMultiValue = false, type = "io.framely.core.da.SlotConfirm",
          isHead = false, triggers = listOf()),
      ),
      "io.framely.core.IContact" to listOf(
      DUSlotMeta(label = "channel", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "id", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.CleanSession" to listOf(
      ),
      "io.framely.core.DontCare" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "io.framely.core.EntityType", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.confirmation.IStatus" to listOf(
      ),
      "io.framely.core.confirmation.Yes" to listOf(
      ),
      "io.framely.core.confirmation.No" to listOf(
      ),
      "io.framely.core.AmountOfMoney" to listOf(
      ),
      "io.framely.core.IIntent" to listOf(
      ),
      "io.framely.core.hasMore.IStatus" to listOf(
      ),
      "io.framely.core.hasMore.No" to listOf(
      ),
      "io.framely.core.HasMore" to listOf(
      DUSlotMeta(label = "status", isMultiValue = false, type = "io.framely.core.hasMore.IStatus",
          isHead = false, triggers = listOf()),
      ),
      "io.framely.core.hasMore.Yes" to listOf(
      ),
      "io.framely.core.Companion" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf()),
      ),
      "io.framely.core.companion.Not" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf()),
      ),
      "io.framely.core.companion.Or" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf()),
      ),
      "io.framely.core.booleanGate.IStatus" to listOf(
      ),
      "io.framely.core.booleanGate.Yes" to listOf(
      ),
      "io.framely.core.booleanGate.No" to listOf(
      ),
      "io.framely.core.IntentClarification" to listOf(
      DUSlotMeta(label = "utterance", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "source", isMultiValue = true, type = "io.framely.core.IIntent", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "io.framely.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.ValueClarification" to listOf(
      DUSlotMeta(label = "source", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.framely.core.NextPage" to listOf(
      ),
      "io.framely.core.PreviousPage" to listOf(
      ),
      "io.framely.core.SlotInit" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.EntityRecord" to listOf(
      DUSlotMeta(label = "label", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "expressions", isMultiValue = true, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.framely.core.user.UserIdentifier" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.user.IUserProfile" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "phone", isMultiValue = false, type = "io.framely.core.PhoneNumber", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "name", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "email", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "userInputCode", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "code", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      ),
      "io.framely.core.user.IUserIdentifier" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.IPersistent" to listOf(
      ),
      "io.framely.core.ISingleton" to listOf(
      ),
      "kotlin.Pair" to listOf(
      ),
      "io.framely.core.IKernelIntent" to listOf(
      ),
      "io.framely.core.ITransactionalIntent" to listOf(
      ),
      "io.framely.core.That" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "T", isHead = true, triggers =
          listOf()),
      ),
      "io.framely.core.SlotClarification" to listOf(
      DUSlotMeta(label = "mentionedSource", isMultiValue = false, type = "io.framely.core.Cell",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "source", isMultiValue = true, type = "io.framely.core.Cell", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "io.framely.core.Cell", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.Cell" to listOf(
      DUSlotMeta(label = "originalSlot", isMultiValue = false, type = "io.framely.core.SlotType",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.framely.core.Ordinal", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.UserSession" to listOf(
      DUSlotMeta(label = "chatbot", isMultiValue = false, type = "io.framely.core.IChatbot", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.framely.core.IChatbot" to listOf(
      ),
      "io.framely.core.IFrame" to listOf(
      ),
      )

  public override val typeAlias: Map<String, List<String>> = mapOf()
}

public interface smsSender : IService {
  @JsonIgnore
  public fun sendSms(phoneNumer: PhoneNumber, content: String): Boolean
}