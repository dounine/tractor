package com.dounine.tractor.model.types.currency

object Offset extends Enumeration {
  val dbLength: Int = 5

  type Offset = Value
  val open: Offset.Value = Value("open")
  val close: Offset.Value = Value("close")
}
