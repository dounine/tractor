package com.dounine.tractor.model.types.currency

object OrderPriceType extends Enumeration {
  val dbLength: Int = 20

  type OrderPriceType = Value

  val limit: OrderPriceType.Value = Value("limit")
  val opponent: OrderPriceType.Value = Value("opponent")
  val postOnly: OrderPriceType.Value = Value("post_only") //maker
  val optimal5: OrderPriceType.Value = Value("optimal_5")
  val lightning: OrderPriceType.Value = Value("lightning") //Lightning closes positions
  val fok: OrderPriceType.Value = Value("fok")
  val ioc: OrderPriceType.Value = Value("ioc")
  val optimal10: OrderPriceType.Value = Value("optimal_10")
  val optimal20: OrderPriceType.Value = Value("optimal_20")

  def list: List[OrderPriceType] =
    List(
      limit,
      opponent,
      postOnly,
      optimal5,
      lightning,
      fok,
      ioc,
      optimal10,
      optimal20
    )

}
