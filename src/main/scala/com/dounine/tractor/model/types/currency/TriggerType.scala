package com.dounine.tractor.model.types.currency

object TriggerType extends Enumeration {
  val dbLength: Int = 2

  type TriggerType = Value

  val le: TriggerType.Value = Value("le")
  val ge: TriggerType.Value = Value("ge")

}
