package com.dounine.tractor.service

import com.dounine.tractor.model.models.{BalanceModel, SliderModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.service.SliderType.SliderType

import scala.concurrent.Future

trait SliderApi {

  def add(info: SliderModel.SliderInfo): Future[Option[Int]]

  def info(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      sliderType: SliderType
  ): Future[SliderModel.SliderInfo]

  def info(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction
  ): Future[Map[SliderType, SliderModel.SliderInfo]]

}
