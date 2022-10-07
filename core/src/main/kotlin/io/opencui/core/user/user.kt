package io.opencui.core.user

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import io.opencui.core.Annotation
import kotlin.reflect.KMutableProperty0

interface IUserIdentifier {
    var userId: String?
    var channelType: String?
    var channelLabel: String?

    fun channelId() : String {
        return if (channelLabel == null) channelType!! else "$channelType+$channelLabel"
    }
    fun uuid(): String {
        return "c|$channelType|$channelLabel|$userId"
    }
}

// Support need profile, omnichannel need profile, and bot need profile for payment.
// For omnichannel, the key is uuid which can be verified phone or email.
// For bot/support, phone or email can be useful.
interface IUserProfile: IUserIdentifier {
    var name: String?
    var phone: PhoneNumber?
    var email: String?
    var code: Int?
    var userInputCode: Int?
}

data class UserInfo(
    override var channelType: String?,
    override var userId: String?,
    override var channelLabel: String?
) : IUserProfile, HashMap<String, Any>() {
    override var name: String? = null
    override var phone: PhoneNumber? = null
    override var email: String? = null
    override var code: Int? = null
    override var userInputCode: Int? = null

    init {
        // safeguard for over fashioned channelType, eventually should go away.
        assert(channelType!!.indexOf('+') == -1)
    }
}

/**
 */
data class UserIdentifier (
@JsonIgnore
override var session: UserSession?
): IFrame, IUserIdentifier, ISingleton {
    override var channelType: String? = null
    override var userId: String? = null
    override var channelLabel: String? = null
    @JsonIgnore
    override lateinit var filler: FrameFiller<*>
    override val type: FrameKind = FrameKind.FRAME
    @JsonIgnore
    override val annotations: Map<String, List<Annotation>> = emptyMap()
    override fun createBuilder(p: KMutableProperty0<out Any?>?) = object : FillBuilder {
        var frame: UserIdentifier? = this@UserIdentifier
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}