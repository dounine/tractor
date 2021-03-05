package com.dounine.tractor.model.types.currency

object TriggerCancelFailStatus extends Enumeration {
  type TriggerCancelFailStatus = Value
  val cancelOrderNotExit = Value("trigger_cancel_order_not_exit")
  val cancelAlreadyCanceled = Value("trigger_cancel_already_canceled")
  val cancelAlreadyMatched = Value("trigger_cancel_already_matched")
  val cancelAlreadyFailed = Value("trigger_cancel_already_failed")
  val cancelTimeout = Value("trigger_cancel_timeout")
}
