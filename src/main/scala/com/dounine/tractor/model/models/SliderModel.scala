package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.service.SliderType.SliderType

object SliderModel {

  final case class SliderInfo(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      system: Boolean,
      sliderType: SliderType,
      min: BigDecimal,
      max: BigDecimal,
      setup: BigDecimal,
      disable: Boolean,
      input: Boolean,
      marks: Map[String, String]
  ) extends BaseSerializer

}
