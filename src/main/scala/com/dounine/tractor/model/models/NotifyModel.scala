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

object NotifyModel {

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
                         price: BigDecimal,
                         volume: Int,
                         tradeVolume: Int,
                         tradeTurnover: BigDecimal,
                         fee: BigDecimal,
                         profit: BigDecimal,
                         trade: List[Trade]
                       ) extends BaseSerializer

  case class Trade(
                    tradeVolume: Int,
                    tradePrice: BigDecimal,
                    tradeFee: BigDecimal,
                    tradeTurnover: BigDecimal,
                    createTime: LocalDateTime,
                    role: Role
                  ) extends BaseSerializer


}
