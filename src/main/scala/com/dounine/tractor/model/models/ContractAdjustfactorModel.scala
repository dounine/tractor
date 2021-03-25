package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate

object ContractAdjustfactorModel {

  final case class Info(
      symbol: CoinSymbol,
      leverRate: LeverRate,
      ladder: Int,
      minSize: Int,
      maxSize: Int,
      adjustFactor: BigDecimal
  ) extends BaseSerializer

  final case class AddItemListLadders(
      ladder: Int,
      min_size: Number,
      max_size: Number,
      adjust_factor: Number
  ) extends BaseSerializer

  final case class AddItemList(
      lever_rate: Int,
      ladders: List[AddItemListLadders]
  ) extends BaseSerializer

  final case class AddItem(
      symbol: CoinSymbol,
      list: List[AddItemList]
  ) extends BaseSerializer

  final case class AddEntity(
      status: String,
      data: List[AddItem]
  ) extends BaseSerializer

}
