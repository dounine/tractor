package com.dounine.tractor.model.types.currency

object EntrustCreateFailStatus extends Enumeration {
  type EntrustCreateFailStatus = Value
  val createTimeout = Value("entrust_create_timeout")
  val createSizeOverflow= Value("entrust_create_size_overflow")
}
