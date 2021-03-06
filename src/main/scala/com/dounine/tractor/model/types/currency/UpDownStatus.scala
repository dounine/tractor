package com.dounine.tractor.model.types.currency

object UpDownStatus extends Enumeration {

  type UpDownStatus = Value

  val dbLength: Int = 20

  val Inited: UpDownStatus.Value = Value("Inited")
  val UnHealth: UpDownStatus.Value = Value("UnHealth")
  val Stoped: UpDownStatus.Value = Value("Stoped")
  val Stopping: UpDownStatus.Value = Value("Stopping")
  val OpenTriggering: UpDownStatus.Value = Value("OpenTriggering")
  val OpenEntrusted: UpDownStatus.Value = Value("OpenEntrusted")
  val OpenPartMatched: UpDownStatus.Value = Value("OpenPartMatched")
  val Opened: UpDownStatus.Value = Value("Opened")
  val OpenErrored: UpDownStatus.Value = Value("OpenErrored")
  val CloseTriggering: UpDownStatus.Value = Value("CloseTriggering")
  val CloseEntrusted: UpDownStatus.Value = Value("CloseEntrusted")
  val ClosePartMatched: UpDownStatus.Value = Value("ClosePartMatched")
  val Closed: UpDownStatus.Value = Value("Closed")
  val CloseErrored: UpDownStatus.Value = Value("CloseErrored")

}
