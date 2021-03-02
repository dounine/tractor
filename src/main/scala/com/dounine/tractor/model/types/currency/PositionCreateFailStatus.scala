package com.dounine.tractor.model.types.currency

object PositionCreateFailStatus extends Enumeration {
  type PositionCreateFailStatus = Value
  val createClosePositionNotExit = Value("create_close_position_not_exit")
  val createCloseNotEnoughIsAvailable = Value("create_close_not_enough_is_available")
  val createSystemError = Value("create_system_error")
}
