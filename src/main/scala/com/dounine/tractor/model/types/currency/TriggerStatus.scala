package com.dounine.tractor.model.types.currency

object TriggerStatus extends Enumeration {
  type TriggerStatus = Value

  val submit: TriggerStatus.Value = Value("trigger_submit")
  val canceled: TriggerStatus.Value = Value("trigger_canceled")
  val matchs: TriggerStatus.Value = Value("trigger_matchs")
  val error: TriggerStatus.Value = Value("trigger_error")

}
