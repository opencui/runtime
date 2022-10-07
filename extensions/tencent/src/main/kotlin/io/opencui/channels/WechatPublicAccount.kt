package io.opencui.channels

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import io.opencui.core.*
import io.opencui.core.user.*
import io.opencui.serialization.JsonObject
import io.opencui.serialization.getPrimitive
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.*
import kotlin.concurrent.timerTask

/**
 * The document for connecting to lark as channel.
 * https://developers.weixin.qq.com/doc/offiaccount/Getting_Started/Overview.html
 * Based on the original python demo code.
 * https://blog.csdn.net/csnewdn/article/details/53407621
 */
interface IWPAPayload : IPayload {
    val type: String
    val msgId: String?
}

//payment card
data class PaymentCardPayload(
    val invoiceID: String,
    val amount: String,
    val prompts: String,
    val errorMessage: String?,
    override val msgId: String?=null): IWPAPayload {
    override val type: String = "payment"
}


//refund card
data class RefundCardPayload(
    val invoiceID: String,
    val transactionCode: String,
    val amount: String, val fee: String,
    val successMessage: String,
    val errorMessage: String,
    override val msgId: String?=null): IWPAPayload {
    override val type: String = "refund"
}

interface BaseMessage {
    // both OpenID
    val ToUserName: String
    val FromUserName: String
    val CreateTime: Long
    val MsgType: String
    val MsgId: Long
    val FuncFlag: Int
}

// There are many messages that we can reply, text is the most common one.
data class Text(    // both OpenID
    override val ToUserName: String,
    override val FromUserName: String,
    override val CreateTime: Long,
    override val MsgType: String,
    override val MsgId: Long,
    override val FuncFlag: Int,
    val Content: String) : BaseMessage


/**
 * For now, we skip the encryption that is detailed in here, so configure plain text on wechat.
 * https://blog.csdn.net/weixin_40877388/article/details/80194004
 * this is used for testing.
 * https://mp.weixin.qq.com/debug/cgi-bin/apiinfo?t=index&type=%E8%87%AA%E5%AE%9A%E4%B9%89%E8%8F%9C%E5%8D%95&form=%E8%87%AA%E5%AE%9A%E4%B9%89%E8%8F%9C%E5%8D%95%E5%88%9B%E5%BB%BA%E6%8E%A5%E5%8F%A3%20/menu/creat
 */
@RestController
@RequestMapping("/org/{org}/bot/{agentId}/")
class WechatPublicAccountResource {


    @GetMapping("c/wechatpa/v1/{label}/{lang}", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getResponse(
        @PathVariable("org") org: String,
        @PathVariable("agentId") agentId: String,
        @PathVariable("label") label: String,
        @PathVariable("lang") lang: String,
        @RequestParam("signature") signature: String,
        @RequestParam("timestamp") timestamp: String,
        @RequestParam("nonce") noncet: String,
        @RequestParam("echostr") echostr: String): ResponseEntity<String> {
        return ResponseEntity.ok(echostr)
    }


    @PostMapping("c/wechatpa/v1/{label}/{lang}",
        consumes = [MediaType.TEXT_XML_VALUE], produces = [MediaType.TEXT_XML_VALUE])
    fun postResponse(
        @PathVariable("org") org: String,
        @PathVariable("agentId") agentId: String,
        @PathVariable("label") label: String,
        @PathVariable("lang") lang: String,
        body: String) {
        val objJson = xmlMapper.readTree(body)

        // For now, we only handles the text messages.
        if (objJson.get(MSGTYPE).asText() == "text") {
            logger.info(objJson.toString())
            val toId = objJson.get(FROM).asText()
            val msgId = objJson.get(MSGID).asText()
            val msgText = objJson.get(CONTENT).asText()
            // Before we process incoming message, we need to acquire user session.
            val userInfo = UserInfo(CHANNELTYPE, toId, label)

            // For now, we assume the language of user session are decided at beginning and
            // not changed after that.
            Dispatcher.process(userInfo, BotInfo(org, agentId, lang), textMessage(msgText, msgId))
        }
    }

    companion object {
        const val CHANNELTYPE = "wechatpa"
        const val MSGTYPE = "MsgType"
        const val MSGID = "MsgId"
        const val FROM = "FromUserName"
        const val TO = "ToUserName"
        const val PHONE = "phone"
        const val EMAIL = "email"
        const val CONTENT = "Content"
        const val IUSERPROFILE = "io.framely.core.user.IUserProfile"
        val xmlMapper = XmlMapper()
        val logger = LoggerFactory.getLogger(WechatPublicAccountResource::class.java)
    }
}


/**
 * One of the technical issues for WeChat public account is maintain the access token,
 * which is expired every 7200 second. It is useful to have a mechanism so that we can
 * continuously update access token for channel to use.
 *
 */


/**
 * https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Template_Message_Interface.html
 */
data class WechatPublicAccountChannel(override val info: Configuration) : IMessageChannel {

    val client = WebClient.builder()
      .baseUrl("https://api.weixin.qq.com/cgi-bin")
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
      .build()

    inline fun <reified T> post(payload: T, path: String): String? {
        val response = client.post()
            .uri(path)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(Mono.just(payload), T::class.java)
            .retrieve()
            .bodyToMono(String::class.java)
        return response.block()
    }

    val label = info.label!!

    override fun getProfile(botInfo: BotInfo, id: String): IUserIdentifier? {
        // TODO: this is not used yet, but ideally this only pick up useful inform from
        // channel so that session can make use of it.
        val accessToken = getAccessTokenLocal(botInfo)
        if (accessToken != "") {
            val target = client.get()
                .uri("/user/info?access_token=$accessToken&openid=$id&lang=zh_CN")

            val res = target.retrieve().bodyToMono(JsonObject::class.java).block()?: return null
            logger.info(res.toString())
            val name = res["nickname"].asText()
            return UserInfo("wechatpa", id, label).apply {
                this.name = name
            }
        }
        return null
    }

    override fun sendRawPayload(uid: String, rawMessage: JsonObject, botInfo: BotInfo, source: IUserIdentifier?): IChannel.Status {
        // POST https://api.weixin.qq.com/cgi-bin/template/api_set_industry?access_token=ACCESS_TOKEN
        return when (rawMessage) {
            // is SimpleTextWithOptionsPayload -> sendSimpleTextWithOptionsPayload(uid, rawMessage, botInfo, source)
            // is SimpleTextWithUrlPayload -> sendSimpleTextWithUrlPayload(uid, rawMessage, botInfo, source)
            // is SimpleTextWithDialPayload -> sendSimpleTextWithDialPayload(uid, rawMessage, botInfo, source)
            // is ListRichPayload -> sendListRichCards(uid, rawMessage, botInfo, source)
            // is PaymentCardPayload -> sendPaymentCardPayload(uid, rawMessage, botInfo, source)
            // is RefundCardPayload -> sendRefundCardPayload(uid, rawMessage, botInfo, source)
            else -> IChannel.Status("Nothing matched")
        }
    }


    override fun sendSimpleText(
        uid: String, rawMessage: TextPayload, botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        // based on the following: https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Service_Center_messages.html#7
        val access_token = getAccessTokenLocal(botInfo)
        if (access_token != "") {
            val uri = "/message/custom/send?access_token=$access_token"
            val content = mapOf("content" to (rawMessage as TextPayload).text)
            val payload = mapOf("touser" to uid, "msgtype" to "text", "text" to content)
            post(payload, uri)

        }
        return IChannel.Status("OK")
    }


    override fun sendListText(uid: String, rawMessage: ListTextPayload, botInfo: BotInfo, source: IUserIdentifier?): IChannel.Status{
        val access_token = getAccessTokenLocal(botInfo)
        if (access_token != "") {
            val uri = "/message/custom/send?access_token=$access_token"
            val payload = rawMessage as ListTextPayload
            val text: String = payload.text
            val body: List<String> = payload.body!!
            val list = mutableListOf<Map<String, String>>()
            for(option in body){
                list.add(mapOf("id" to UUID.randomUUID().toString() , "content" to option))
            }
            var description = ""
            val msgmenu = mapOf("head_content" to "${text}\n${description}", "list" to list)
            val msg = mapOf("touser" to uid, "msgtype" to "msgmenu", "msgmenu" to msgmenu)

            logger.info(mapper.writeValueAsString(msg))

            val rres = post(msg, uri)

            var tail_content = ""
            val listInputAction: MutableList<Map<String, String>> = mutableListOf<Map<String, String>>()
            if(!payload.inputActions.isNullOrEmpty()){
                val inputActions = payload.inputActions!!
                for(action in inputActions){
                    if(action is Reply) listInputAction.add(mapOf("id" to UUID.randomUUID().toString(), "content" to action.display))
                    if(action is Click) {
                        tail_content += "${action.display} open ${action.url}\n"
                    }
                    if(action is Call){
                        tail_content += "${action.display} call ${action.phoneNumber}\n"
                    }
                }
            }
            val msgmenuInputAction = mutableMapOf("head_content" to "也许你想了解如下内容：", "list" to listInputAction)
            if(!tail_content.isNullOrEmpty()){
                msgmenuInputAction.put("tail_content", tail_content)
            }
            val msgTwo = mapOf("touser" to uid, "msgtype" to "msgmenu", "msgmenu" to msgmenuInputAction)
            post(msgTwo, uri)
            logger.info(rres.toString())
        }
        return IChannel.Status("OK")
    }

    override fun sendRichCard(
        uid: String, rawMessage: RichPayload, botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status{
        val access_token = getAccessTokenLocal(botInfo)
        if (access_token != "") {
            val uri = "/message/custom/send?access_token=$access_token"
            val payload = rawMessage as RichPayload
            val title = payload.title
            var description = payload.description
            val fileUrl = payload.richMedia!!.fileUrl
            val list: MutableList<Map<String, String>> = mutableListOf()
            var tail_content = ""
            val articles = mutableListOf<MutableMap<String, String>>()
            val article = mutableMapOf("title" to title, "picurl" to fileUrl)

            if(!payload.insideActions.isNullOrEmpty()){
                // TODO(sean): Need to bring this back
                // val click: Click = payload.cardInputAction as Click
                // article.put("url", click.url)
                // description += "\n${click.display}"
            }
            article.put("description", description)
            payload.floatActions?.map{
                list.add(mapOf("id" to UUID.randomUUID().toString(), "content" to it.display))
            }

            articles.add(article)
            val msgmenu = mutableMapOf("head_content" to "也许你想了解如下内容：", "list" to list)
            if(!tail_content.isNullOrEmpty()){
                msgmenu.put("tail_content", tail_content)
            }
            val msgOne = mapOf("touser" to uid, "msgtype" to "news", "news" to mapOf("articles" to articles))
            val msgTwo = mapOf("touser" to uid, "msgtype" to "msgmenu", "msgmenu" to msgmenu)
            post(msgOne, uri)
            post(msgTwo, uri)
        }

        return IChannel.Status("OK")
    }

    override fun sendListRichCards(
        uid: String, rawMessage: ListRichPayload, botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status{
        val access_token = getAccessTokenLocal(botInfo)
        if (access_token != "") {
            val uri = "/message/custom/send?access_token=$access_token"
            val text = rawMessage.text
            val titles: List<String> = rawMessage.cardTitles!!
            val descriptions: List<String> = rawMessage.cardDescriptions!!
            val fileUrls: List<String> = rawMessage.fileUrls!!
            val cardInputActions: List<List<ClientAction>> = rawMessage.cardInputActions!!

            val msgOne = mapOf("touser" to uid, "msgtype" to "text", "text" to mapOf("content" to text))
            post(msgOne, uri)
            for (index in 0..fileUrls.size - 1) {
                val map = mutableMapOf("title" to titles[index], "picurl" to fileUrls[index])
                map.put("description", descriptions[index])
                val articles = listOf(map)
                val msgTwo = mapOf("touser" to uid, "msgtype" to "news", "news" to mapOf("articles" to articles))
                post(msgTwo, uri)
                val listCardInputAction: MutableList<Map<String, String>> = mutableListOf()
                var tail_content = ""
                if (cardInputActions != null && cardInputActions[index] != null) {
                    val cardInputAction = cardInputActions[index]
                    for (action in cardInputAction) {
                        if (action is Reply) listCardInputAction.add(
                            mapOf(
                                "id" to UUID.randomUUID().toString(),
                                "content" to action.display
                            )
                        )
                        if (action is Click) {
                            tail_content += "${action.display} open ${action.url}\n"
                        }
                        if (action is Call) {
                            tail_content += "${action.display} call ${action.phoneNumber}\n"
                        }
                    }
                }
                val msgmenu = mutableMapOf("head_content" to "也许你想了解如下内容：", "list" to listCardInputAction)
                if (!tail_content.isNullOrEmpty()) {
                    msgmenu.put("tail_content", tail_content)
                }
                val msgCardInputAction = mapOf("touser" to uid, "msgtype" to "msgmenu", "msgmenu" to msgmenu)
                post(msgCardInputAction, uri)
            }
            if (!rawMessage.mainInputActions.isNullOrEmpty()) {
                val list = mutableListOf<Map<String, String>>()
                val mainInputActions = rawMessage.mainInputActions!!
                var tail_content = ""
                for (index in 0..(mainInputActions.size - 1)) {
                    val action = mainInputActions[index]
                    when (action) {
                        is Reply -> list.add(mapOf("id" to UUID.randomUUID().toString(), "content" to action.display))
                        is Click -> tail_content += "${action.display} open ${action.url}\n"
                        is Call -> tail_content += "${action.display} call ${action.phoneNumber}\n"
                    }
                }
                val msgThree = mapOf(
                    "touser" to uid,
                    "msgtype" to "msgmenu",
                    "msgmenu" to mapOf("list" to list, "tail_content" to tail_content)
                )
                // TODO: why we have this?
                Thread.sleep(1000)
                post(msgThree, uri)
            }
            logger.info("success send listRichCard message")
        }

        return IChannel.Status("OK")
    }

    fun getAccessTokenLocal(botInfo: BotInfo) : String {
        return Dispatcher.sessionManager.botStore!!.getRaw(botInfo, "wechat:$label:access_token")!!
    }

    fun getAccessTokenRemote(botInfo: BotInfo) : String ? {
        val id = info.get("app_id")
        val secret = info.get("app_secret")
        val endpoint = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=$id&secret=$secret"

        val rres = client.get()
            .uri("/token?grant_type=client_credential&appid=$id&secret=$secret")
            .retrieve()
            .bodyToMono(JsonObject::class.java)
            .block()?: return null

        return rres.getPrimitive("access_token").asText()
    }


    companion object : ExtensionBuilder<IChannel> {
        val logger = LoggerFactory.getLogger(WechatPublicAccountChannel::class.java)
        val mapper: ObjectMapper = ObjectMapper()

        val channelType = "wechatpa"
        //The value of this number is based on the following: https://developers.weixin.qq.com/doc/offiaccount/Basic_Information/Get_access_token.html
        //the access_token's validity period is 7200000 milliseconds. we need to refresh at least every 7200000 milliseconds, so 6900000 is reasonable.
        private const val delay: Long = 6900000

        override fun invoke(botConfig: Configuration): IChannel {
            val channel = WechatPublicAccountChannel(botConfig)
            val label = botConfig.label

            val org = botConfig["org"]?: ""
            val bot = botConfig["agent"]?: ""
            val botInfo = BotInfo(org as String, bot as String, "*", "master")

            // We create the repeated refreshing of access token for this bot/label.
            Dispatcher.timer.scheduleAtFixedRate(
                timerTask {
                    val accessToken = channel.getAccessTokenRemote(botInfo)
                    val key = "wechat:$label:access_token"
                    Dispatcher.sessionManager.botStore?.putRaw(botInfo, key, accessToken?:"")

                },
                0, delay)

            return channel
        }

    }
}
