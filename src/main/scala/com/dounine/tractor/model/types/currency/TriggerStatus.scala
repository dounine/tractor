package com.dounine.tractor.model.types.currency

object TriggerStatus extends Enumeration {
  type TriggerStatus = Value

  val submit: TriggerStatus.Value = Value("submit")
  val canceled: TriggerStatus.Value = Value("canceled")
  val matchs: TriggerStatus.Value = Value("matchs")
  val error: TriggerStatus.Value = Value("error")

}
