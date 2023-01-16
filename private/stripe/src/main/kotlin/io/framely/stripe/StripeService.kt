package io.framely.stripe

import com.stripe.Stripe
import com.stripe.exception.*
import com.stripe.model.Charge
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class StripeService {
    @Value("\${STRIPE_SECRET_KEY}")
    var secretKey: String? = null
    @PostConstruct
    fun init() {
        Stripe.apiKey = secretKey
    }

    @Throws(
        AuthenticationException::class,
        InvalidRequestException::class,
        APIConnectionException::class,
        CardException::class,
        APIException::class
    )
    fun charge(chargeRequest: ChargeRequest): Charge {
        val chargeParams: MutableMap<String, Any?> = HashMap()
        chargeParams["amount"] = chargeRequest.amount
        chargeParams["currency"] = chargeRequest.currency
        chargeParams["description"] = chargeRequest.description
        chargeParams["source"] = chargeRequest.stripeToken
        return Charge.create(chargeParams)
    }
}