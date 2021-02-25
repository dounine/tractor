package com.dounine.tractor.model.types.currency

object EntrustStatus extends Enumeration {
  type EntrustStatus = Value

  val submit: EntrustStatus.Value = Value("entrust_submit")
  val canceled: EntrustStatus.Value = Value("entrust_canceled")
  val matchAll: EntrustStatus.Value = Value("entrust_match_all")
  val matchPart: EntrustStatus.Value = Value("entrust_match_part")
  val matchPartOtherCancel: EntrustStatus.Value = Value("entrust_match_part_other_cancel")
  val error: EntrustStatus.Value = Value("entrust_error")

}
