package com.dounine.tractor.service

import com.dounine.tractor.model.models.ContractAdjustfactorModel
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol

import scala.concurrent.Future

trait ContractAdjustfactorApi {

  def infos(symbol: CoinSymbol): Future[Seq[ContractAdjustfactorModel.Info]]

}
