package io.opencui.core.booleanGate

import com.fasterxml.jackson.annotation.JsonIgnore
import io.opencui.core.*
import io.opencui.core.Annotation
import kotlin.reflect.KMutableProperty0


interface IStatus


data class Yes(
    override var session: UserSession? = null
) : IFrame, IStatus {
    @JsonIgnore
    override val type: FrameKind = FrameKind.FRAME

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: Yes? = this@Yes

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}


data class No(
    override var session: UserSession? = null
) : IFrame, IStatus {
    @JsonIgnore
    override val type: FrameKind = FrameKind.FRAME

    @JsonIgnore
    override var annotations: Map<String, List<Annotation>> = mutableMapOf()

    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: No? = this@No

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}
