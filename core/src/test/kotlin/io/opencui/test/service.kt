package io.opencui.test

import io.opencui.core.*
import io.opencui.provider.ITemplatedProvider
import io.opencui.provider.IConnection
import io.opencui.provider.SqlConnection
import io.opencui.serialization.Json

interface IVacationService: IService {
    fun search_flight(origin: String?, destination: String?): List<String>
    fun searchHotel(): List<Hotel>
    fun searchHotelByCity(city: String?): List<String>
    fun hotel_address(hotel: Hotel?): HotelAddress
}

interface IMobileService: IService {
    fun search_mobile(cellphone: String?): List<Mobile>
    fun search_cellphone(name: String?): List<MobileWithAdvancesForMapping>
}

interface IIntentSuggestionService: IService {
    fun searchIntents(): List<IIntent>
    fun searchIntentsByCurrent(current: InternalNode?): List<IIntent>
}

interface IDishService: IService {
    fun recDish(): List<Dish>
}


data class VacationServiceTemplateImpl(override var session: UserSession?, override var provider: IConnection?): IVacationService, ITemplatedProvider{
    override fun search_flight(origin: String?, destination: String?): List<String> {
        val sql = """select flight as flight from flight where origin = '${origin}' and destination = '${destination}'"""
        return (provider as IConnection).invoke(mapOf(), mapOf("origin" to origin, "destination" to destination), sql, Json.getListConverter(Json.getEntityConverter(String::class.java)), isList = true)
    }
    override fun searchHotel(): List<Hotel> {
        val sql = """select hotel as hotel, city as city from hotel"""
        return (provider as IConnection).invoke(mapOf(), mapOf(), sql, Json.getListConverter(Json.getFrameConverter(session, Hotel::class.java)), isList = true)
    }
    override fun searchHotelByCity(city: String?): List<String> {
        val sql = """select hotel as hotel from hotel where city = '${city}'"""
        return (provider as IConnection).invoke(mapOf(), mapOf("city" to city), sql, Json.getListConverter(Json.getEntityConverter(String::class.java)), isList = true)
    }
    override fun hotel_address(hotel: Hotel?): HotelAddress {
        val sql = """select address from hotel_address where hotel = '${hotel?.hotel}'"""
        return (provider as IConnection).invoke(mapOf(), mapOf("hotel" to hotel), sql, Json.getFrameConverter(session, HotelAddress::class.java), isList = false)
    }

    companion object : ExtensionBuilder<IVacationService> {
        override fun invoke(config: Configuration): IVacationService {
            val conn = SqlConnection(config)
            return VacationServiceTemplateImpl(null, conn)
        }
    }
}

data class MobileServiceTemplateImpl(override var session: UserSession?, override var provider: IConnection?): IMobileService, ITemplatedProvider {
    override fun search_mobile(cellphone: String?): List<Mobile> {
        val sql = """select id, amount from mobile where cellphone = '${cellphone}'"""
        return (provider as IConnection).invoke(
            mapOf(),
            mapOf(),
            sql,
            Json.getListConverter(Json.getFrameConverter(session, Mobile::class.java)),
            isList = true
        )
    }

    override fun search_cellphone(name: String?): List<MobileWithAdvancesForMapping> {
        val sql = """select id, cellphoneMapping from cellphone where nameMapping = '${name}'"""
        return (provider as IConnection).invoke(
            mapOf(),
            mapOf("name" to name),
            sql,
            Json.getListConverter(Json.getFrameConverter(session, MobileWithAdvancesForMapping::class.java)),
            isList = true
        )
    }

    companion object : ExtensionBuilder<IMobileService> {
        override fun invoke(config: Configuration): IMobileService {
            val conn = SqlConnection(config)
            return MobileServiceTemplateImpl(null, conn)
        }
    }
}

data class IntentSuggestionServiceTemplateImpl(override var session: UserSession?, override var provider: IConnection?): IIntentSuggestionService, ITemplatedProvider{
    override fun searchIntents(): List<IIntent> {
        val sql = """select "@class" as "@class", current_node as current from intents where node_state = 'root'"""
        return (provider as IConnection).invoke(mapOf(), mapOf(), sql, Json.getListConverter(Json.getInterfaceConverter(session!!)),isList = true)
    }
    override fun searchIntentsByCurrent(current: InternalNode?): List<IIntent> {
        val sql = """select "@class" as "@class", current_node as current from intents where node_state = '${current}'"""
        return (provider as IConnection).invoke(mapOf(), mapOf("current" to current), sql, Json.getListConverter(Json.getInterfaceConverter(session!!)),isList = true)
    }

    companion object: ExtensionBuilder<IIntentSuggestionService> {
        override fun invoke(config: Configuration): IIntentSuggestionService {
            println("building service...")
            val conn = SqlConnection(config)
            return IntentSuggestionServiceTemplateImpl(null, provider = conn)
        }
    }
}

data class DishServiceTemplateImpl(override var session: UserSession?, override var provider: IConnection?): IDishService, ITemplatedProvider{

    override fun recDish(): List<Dish> {
        val sql = """select dish as dish from dish"""
        return (provider as IConnection).invoke(mapOf(), mapOf(), sql, Json.getListConverter(Json.getEntityConverter(Dish::class.java)),isList = true)
    }

    companion object: ExtensionBuilder<IDishService> {
        override fun invoke(config: Configuration): IDishService {
            println("building service...")
            val conn = SqlConnection(config)
            return DishServiceTemplateImpl(null, provider = conn)
        }
    }
}