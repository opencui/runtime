package io.framely.stripe

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class CheckoutController {
    @Value("\${STRIPE_PUBLIC_KEY}")
    private val stripePublicKey: String? = null
    @RequestMapping("/checkout")
    fun checkout(model: Model): String {
        model.addAttribute("amount", 50 * 100) // in cents
        model.addAttribute("stripePublicKey", stripePublicKey)
        model.addAttribute("currency", ChargeRequest.Currency.EUR)
        return "checkout"
    }
}