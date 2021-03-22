package com.dounine.tractor.model.types.currency

object EntrustCreateFailStatus extends Enumeration {
  type EntrustCreateFailStatus = Value
  val createTimeout = Value("entrust_create_timeout")
  val createSizeOverflow = Value("entrust_create_size_overflow")
  val createPositionNotExit = Value("entrust_create_position_not_exit")
  val createPositionNotEnoughIsAvailable = Value(
    "entrust_create_position_not_enough_is_available"
  )
  val createAvailableGuaranteeIsInsufficient = Value(
    "entrust_create_available_guarantee_is_insufficient"
  )
}
