package com.dounine.tractor.model.types.currency

object TriggerCreateFailStatus extends Enumeration {
  type TriggerCreateFailStatus = Value
  val createTimeout = Value("trigger_create_timeout")
  val createFireTrigger = Value("trigger_create_fire_trigger")

}
