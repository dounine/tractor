package com.dounine.tractor.model.types.currency

object OrderType extends Enumeration {
  type OrderType = Value
  //report order
  val statement: OrderType.Value = Value("order_statement")
  //cancel order
  val withdrawal: OrderType.Value = Value("order_with_drawal")
  //force the closing of the position
  val strongFlat: OrderType.Value = Value("order_strong_flat")
  val delivery: OrderType.Value = Value("order_delivery")

}
