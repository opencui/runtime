package io.framely.stripe

import com.stripe.exception.StripeException
import com.stripe.model.Charge
import lombok.extern.java.Log
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping

@Log
@Controller
class ChargeController {
    @Autowired
    var paymentsService: StripeService? = null
    @PostMapping("/charge")
    @Throws(StripeException::class)
    fun charge(chargeRequest: ChargeRequest, model: Model): String {
        chargeRequest.description = "Example charge"
        chargeRequest.currency = ChargeRequest.Currency.EUR
        val charge: Charge = paymentsService!!.charge(chargeRequest)
        model.addAttribute("id", charge.getId())
        model.addAttribute("status", charge.getStatus())
        model.addAttribute("chargeId", charge.getId())
        model.addAttribute("balance_transaction", charge.getBalanceTransaction())
        return "result"
    }

    @ExceptionHandler(StripeException::class)
    fun handleError(model: Model, ex: StripeException): String {
        model.addAttribute("error", ex.message)
        return "result"
    }
}