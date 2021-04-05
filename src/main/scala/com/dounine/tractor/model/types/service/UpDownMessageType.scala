package com.dounine.tractor.model.types.service

object UpDownMessageType extends Enumeration {
  type UpDownMessageType = Value
  val slider: UpDownMessageType.Value = Value("msg_type_updown_slider")
  val update: UpDownMessageType.Value = Value("msg_type_updown_update")
  val info: UpDownMessageType.Value = Value("msg_type_updown_info")
}
