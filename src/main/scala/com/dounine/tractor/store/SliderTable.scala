package com.dounine.tractor.store

import com.dounine.tractor.model.models.{SliderModel, UserModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction
}
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.service.SliderType
import com.dounine.tractor.model.types.service.SliderType.SliderType
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

class SliderTable(tag: Tag)
    extends Table[SliderModel.SliderInfo](
      _tableTag = tag,
      _tableName = "tractor-slider"
    )
    with EnumMapper {

  import O._

  override def * : ProvenShape[SliderModel.SliderInfo] =
    (
      phone,
      symbol,
      contractType,
      direction,
      system,
      sliderType,
      min,
      max,
      setup,
      disable,
      input,
      marks
    ).mapTo[SliderModel.SliderInfo]

  def phone: Rep[String] = column[String]("phone", Length(13))

  def symbol: Rep[CoinSymbol] =
    column[CoinSymbol]("symbol", Length(CoinSymbol.dbLength))

  def contractType: Rep[ContractType] =
    column[ContractType]("contractType", Length(ContractType.dbLength))

  def direction: Rep[Direction] =
    column[Direction]("direction", Length(Direction.dbLength))

  def system: Rep[Boolean] = column[Boolean]("system", Length(1))

  def sliderType: Rep[SliderType] =
    column[SliderType]("sliderType", Length(SliderType.dbLength))

  def min: Rep[BigDecimal] =
    column[BigDecimal]("min", SqlType("decimal(10, 4)"))

  def max: Rep[BigDecimal] =
    column[BigDecimal]("max", SqlType("decimal(10, 4)"))

  def setup: Rep[BigDecimal] =
    column[BigDecimal]("setup", SqlType("decimal(10, 4)"))

  def disable: Rep[Boolean] = column[Boolean]("disable", Length(1))

  def input: Rep[Boolean] = column[Boolean]("input", Length(1))

  def marks: Rep[Map[String, String]] =
    column[Map[String, String]]("marks", Length(100))

  def pk: PrimaryKey =
    primaryKey(
      "tractor-slider-primaryKey",
      (phone, symbol, contractType, direction, system, sliderType)
    )

}
