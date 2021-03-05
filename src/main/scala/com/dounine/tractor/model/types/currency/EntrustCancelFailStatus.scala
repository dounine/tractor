package com.dounine.tractor.model.types.currency

object EntrustCancelFailStatus extends Enumeration {
  type EntrustCancelFailStatus = Value
  val cancelOrderNotExit = Value("entrust_cancel_order_not_exit")
  val cancelAlreadyCanceled = Value("entrust_cancel_already_canceled")
  val cancelAlreadyMatchAll = Value("entrust_cancel_already_match_all")
  val cancelAlreadyMatchPartCancel = Value("entrust_cancel_already_match_part_cancel")
  val cancelAlreadyFailed = Value("entrust_cancel_already_failed")
  val cancelTimeout = Value("entrust_cancel_timeout")
}
