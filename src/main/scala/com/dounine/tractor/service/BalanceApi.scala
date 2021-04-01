package com.dounine.tractor.service

import com.dounine.tractor.model.models.BalanceModel
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol

import scala.concurrent.Future

trait BalanceApi {

  def balance(
      phone: String,
      symbol: CoinSymbol
  ): Future[Option[BalanceModel.Info]]

  def mergeBalance(
      phone: String,
      symbol: CoinSymbol,
      balance: BigDecimal
  ): Future[Option[BigDecimal]]
}
