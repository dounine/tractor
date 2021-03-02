package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol

import java.time.LocalDateTime

object BalanceModel {

  final case class Info(
                         phone: String,
                         symbol: CoinSymbol,
                         balance: Double,
                         createTime: LocalDateTime
                       ) extends BaseSerializer

}
