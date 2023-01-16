package services.framely.aliyuncs

import com.aliyuncs.DefaultAcsClient
import com.aliyuncs.IAcsClient
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse
import com.aliyuncs.http.MethodType
import com.aliyuncs.profile.DefaultProfile
import com.aliyuncs.profile.IClientProfile
import com.fasterxml.jackson.annotation.JsonIgnore
import io.framely.core.*
import org.slf4j.LoggerFactory
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber

public interface smsSender : IService {
  @JsonIgnore
  public fun sendSms(phoneNumer: PhoneNumber, content: String): Boolean
}

data class AliSmsSender(val info: Configuration): smsSender {

    val accessKeyId = System.getProperty("ali.sms.accessKeyId")
    val accessKeySecret = System.getProperty("ali.sms.accessKeySecret")
    val signName = System.getProperty("ali.sms.signName")
    val domesticTemplateCode = System.getProperty("ali.sms.domesticTemplateCode")
    val internationalTemplateCode = System.getProperty("ali.sms.internationalTemplateCode")
    val profile: IClientProfile = DefaultProfile.getProfile("cn-hangzhou", accessKeyId, accessKeySecret)

    val acsClient: IAcsClient = DefaultAcsClient(profile)

    init {
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000")
        System.setProperty("sun.net.client.defaultReadTimeout", "10000")
        DefaultProfile.addEndpoint("cn-hangzhou", "cn-hangzhou", product, domain)
    }

    override fun sendSms(phoneNumber: PhoneNumber, content: String): Boolean {
        val phoneNumberStr = phoneNumber.toString()
        val request = SendSmsRequest()

        request.phoneNumbers = phoneNumberStr
        request.signName = signName

        if(phoneNumberStr.startsWith("86"))
            request.templateCode = domesticTemplateCode
        else
            request.templateCode = internationalTemplateCode
        request.sysMethod = MethodType.GET

        request.templateParam = content
        try{
            val sendSmsResponse = acsClient.getAcsResponse(request)
            logger.info("status: ${sendSmsResponse.code} message: ${sendSmsResponse.message}")
            return true
        }catch (e: Exception){
            return false
        }
    }

    fun phoneNumberIsValid(phoneNumber: String?, countryCode: String): Boolean{
        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        val numberProto: Phonenumber.PhoneNumber = phoneNumberUtil.parse("+$countryCode$phoneNumber", countryCode)
        return phoneNumberUtil.isValidNumber(numberProto)
    }

    companion object: ExtensionBuilder<smsSender> {
        const val product = "Dysmsapi"
        const val domain = "dysmsapi.aliyuncs.com"
        val logger = LoggerFactory.getLogger(AliSmsSender::class.java)

        override fun invoke(config: Configuration): smsSender {
            return AliSmsSender(config)
        }
    }
}