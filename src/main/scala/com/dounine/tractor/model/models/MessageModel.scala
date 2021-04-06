package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.UpDownUpdateType.UpDownUpdateType
import com.dounine.tractor.model.types.service.MessageType.MessageType
import com.dounine.tractor.model.types.service.UpDownMessageType.UpDownMessageType

object MessageModel {

  final case class Data[T](
      `type`: MessageType,
      data: T
  ) extends BaseSerializer

  final case class UpDownData[T](
      ctype: UpDownMessageType,
      data: T
  ) extends BaseSerializer

  final case class UpDownUpdate(
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      name: UpDownUpdateType,
      value: String
  ) extends BaseSerializer

  final case class UpDownSlider(
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      offset: Offset
  ) extends BaseSerializer

  final case class UpDownInfo(
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction
  ) extends BaseSerializer

}
