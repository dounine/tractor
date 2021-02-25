package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.TriggerType.TriggerType

import java.time.LocalDateTime

object TriggerModel {

  final case class Info(
                         phone: String,
                         symbol: CoinSymbol,
                         orderId: String,
                         contractType: ContractType,
                         direction: Direction,
                         leverRate: LeverRate,
                         offset: Offset,
                         orderPriceType: OrderPriceType,
                         triggerType: TriggerType,
                         orderPrice: Double,
                         triggerPrice: Double,
                         volume: Int,
                         createTime: LocalDateTime
                       ) extends BaseSerializer



}
