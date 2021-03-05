package com.dounine.tractor.model.types.currency

object UpDownUpdateType extends Enumeration {
  type UpDownUpdateType = Value

  val run: UpDownUpdateType.Value = Value("run")
  val runLoading: UpDownUpdateType.Value = Value("runLoading")

  val status: UpDownUpdateType.Value = Value("status")

  val openTriggerPrice: UpDownUpdateType.Value = Value("openTriggerPrice")
  val openReboundPrice: UpDownUpdateType.Value = Value("openReboundPrice")
  val openTriggerPriceSpread: UpDownUpdateType.Value = Value(
    "openTriggerPriceSpread"
  )
  val openVolume: UpDownUpdateType.Value = Value("openVolume")
  val openEntrustTimeout: UpDownUpdateType.Value = Value("openEntrustTimeout")
  val openScheduling: UpDownUpdateType.Value = Value("openScheduling")
  val openLeverRate: UpDownUpdateType.Value = Value("openLeverRate")

  val closeZoom: UpDownUpdateType.Value = Value("closeZoom")
  val closeTriggerPrice: UpDownUpdateType.Value = Value("closeTriggerPrice")
  val closeReboundPrice: UpDownUpdateType.Value = Value("closeReboundPrice")
  val closeTriggerPriceSpread: UpDownUpdateType.Value = Value(
    "closeTriggerPriceSpread"
  )
  val closeVolume: UpDownUpdateType.Value = Value("closeVolume")
  val closeGetInProfit: UpDownUpdateType.Value = Value("closeGetInProfit")
  val closeEntrustTimeout: UpDownUpdateType.Value = Value("closeEntrustTimeout")
  val closeScheduling: UpDownUpdateType.Value = Value("closeScheduling")

}
