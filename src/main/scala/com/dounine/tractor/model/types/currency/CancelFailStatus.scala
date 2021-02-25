package com.dounine.tractor.model.types.currency

object CancelFailStatus extends Enumeration {
  type CancelFailStatus = Value
  val cancelOrderNotExit = Value("cancel_order_not_exit")
  val cancelAlreadyCanceled = Value("cancel_already_canceled")
  val cancelAlreadyMatched = Value("cancel_already_matched")
  val cancelAlreadyFailed = Value("cancel_already_failed")
}
