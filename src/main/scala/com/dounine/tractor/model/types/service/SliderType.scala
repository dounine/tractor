package com.dounine.tractor.model.types.service

object SliderType extends Enumeration {
  val dbLength: Int = 50

  type SliderType = Value
  val openOnline: SliderType.Value = Value("slider_open_online")
  val openReboundPrice: SliderType.Value = Value("slider_open_rebound_price")
  val openPlanPriceSpread: SliderType.Value = Value(
    "slider_open_plan_price_spread"
  )
  val openScheduling: SliderType.Value = Value("slider_open_scheduling")
  val openEntrustTimeout: SliderType.Value = Value(
    "slider_open_entrust_timeout"
  )
  val openVolume: SliderType.Value = Value("slider_open_volume")
  val closeOnline: SliderType.Value = Value("slider_close_online")
  val closeReboundPrice: SliderType.Value = Value("slider_close_rebound_price")
  val closeBoarding: SliderType.Value = Value("slider_close_boarding")
  val closePlanPriceSpread: SliderType.Value = Value(
    "slider_close_plan_price_spread"
  )
  val closeEntrustTimeout: SliderType.Value = Value(
    "slider_close_entrust_timeout"
  )

  val list = List(
    openOnline,
    openReboundPrice,
    openPlanPriceSpread,
    openScheduling,
    openEntrustTimeout,
    openVolume,
    closeOnline,
    closeReboundPrice,
    closeBoarding,
    closePlanPriceSpread,
    closeEntrustTimeout
  )
}
