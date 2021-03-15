package com.dounine.tractor.model.types.currency

object UpDownSubType extends Enumeration {
  type UpDownSubType = Value

  val all: UpDownSubType.Value = Value("updown_sub_all")
  val trigger: UpDownSubType.Value = Value("updown_sub_trigger")
}
