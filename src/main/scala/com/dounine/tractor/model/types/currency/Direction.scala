package com.dounine.tractor.model.types.currency

object Direction extends Enumeration {
  type Direction = Value
  val sell: Direction.Value = Value("sell")
  val buy: Direction.Value = Value("buy")

  val dbLength: Int = 4


  def reverse(value: Direction): Direction = {
    value match {
      case `sell` => buy
      case `buy`  => sell
    }
  }

  def list: List[Direction] = List(sell, buy)
}
