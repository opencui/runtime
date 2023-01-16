package io.framely.stripe

import lombok.Data

@Data
class ChargeRequest {
    enum class Currency {
        EUR, USD
    }

    var description: String? = null
    val amount // cents
            = 0
    var currency: Currency? = null
    val stripeEmail: String? = null
    val stripeToken: String? = null
}