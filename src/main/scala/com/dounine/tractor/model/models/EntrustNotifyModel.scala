package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.EntrustStatus.EntrustStatus
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.OrderType.OrderType
import com.dounine.tractor.model.types.currency.Role.Role
import com.dounine.tractor.model.types.currency.Source.Source

import java.time.LocalDateTime

object EntrustNotifyModel {

  case class NotifyInfo(
                         orderId: String,
                         symbol: CoinSymbol,
                         contractType: ContractType,
                         direction: Direction,
                         offset: Offset,
                         leverRate: LeverRate,
                         orderPriceType: OrderPriceType,
                         entrustStatus: EntrustStatus,
                         source: Source,
                         orderType: OrderType,
                         createTime: LocalDateTime,
                         price: Double,
                         volume: Int,
                         tradeVolume: Int,
                         tradeTurnover: Double,
                         fee: Double,
                         profit: Double,
                         trade: List[Trade]
                       ) extends BaseSerializer

  case class Trade(
                    tradeVolume: Int,
                    tradePrice: Double,
                    tradeFee: Double,
                    tradeTurnover: Double,
                    createTime: LocalDateTime,
                    role: Role
                  ) extends BaseSerializer


}
