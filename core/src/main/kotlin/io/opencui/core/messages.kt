package io.opencui.core

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.io.Serializable

/**
 * Universal message is idea that we can have a set of predefined messages that can be rendered
 * by most of the common messaging platforms: imessage, rcs, messenger, whatsapp, instagram,
 * google business messages, wechat public account.
 *
 * ClientAction are the ones that can be triggered by user on the client for some client side behavior.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    value = [
        JsonSubTypes.Type(value = Reply::class, name = "reply"),
        JsonSubTypes.Type(value = Click::class, name = "click"),
        JsonSubTypes.Type(value = Call::class, name = "call"),
    ]
)
sealed interface ClientAction : Serializable

@JsonTypeName("reply")
data class Reply(val display: String, val payload: String=""): ClientAction
@JsonTypeName("click")
data class Click(val url: String, val display: String, val payload: String=""): ClientAction

@JsonTypeName("call")
data class Call(val phoneNumber: String, val display: String, val payload: String=""): ClientAction

interface IPayload : Serializable

data class RawPayload(val context: String) : IPayload

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    value = [
        JsonSubTypes.Type(value = TextPayload::class, name = "text"),
        JsonSubTypes.Type(value = RichPayload::class, name = "rich"),
        JsonSubTypes.Type(value = ListTextPayload::class, name = "listText"),
        JsonSubTypes.Type(value = ListRichPayload::class, name = "listRich"),
    ]
)
sealed interface IWhitePayload: IPayload {
    val msgId: String?
}

@JsonTypeName("text")
data class TextPayload(
    val text: String = "",
    override val msgId: String?=null) : IWhitePayload {
}

fun textMessage(text: String, msgId: String?=null) : TextPayload {
    return  TextPayload(text, msgId)
}



//list text type
@JsonTypeName("listText")
data class ListTextPayload(
    val flavor: String="",
    val text: String = "",
    val body: List<String>?=null,
    val inputActions: List<ClientAction>?=null,
    override val msgId: String?=null): IWhitePayload {
}


// Should we make altText required or optional, I fill it should be required.
data class RichMedia(
    val fileUrl: String,
    val altText: String,
    val height: HeightOption = HeightOption.HEIGHT_UNSPECIFIED,
    val thumbnailUrl: String? = null,
    val forceRefresh: Boolean = true
) : Serializable {
    enum class HeightOption {
        HEIGHT_UNSPECIFIED,
        SHORT,
        MEDIUM,
        TALL
    }
}

@JsonTypeName("rich")
//Rich card message
data class RichPayload(
    val title: String,
    val description: String,
    val richMedia: RichMedia? = null,
    val insideActions: List<ClientAction>? = null,
    override val msgId: String?=null): IWhitePayload {
    val floatActions: List<Reply>? = null // NOT exposed yet.
}

@JsonTypeName("listRich")
//list rich card message
data class ListRichPayload(
    val flavor: String?=null,
    val text: String="",
    val cardTitles: List<String>?=null,
    val cardDescriptions: List<String>?=null,
    val fileUrls: List<String>?=null,
    val cardInputActions: List<List<ClientAction>>?=null,
    val mainInputActions: List<ClientAction>?=null,
    override val msgId: String?=null): IWhitePayload {
}


