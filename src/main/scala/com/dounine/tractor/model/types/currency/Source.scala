package com.dounine.tractor.model.types.currency

object Source extends Enumeration {
  type Source = Value
  val trigger: Source.Value = Value("trigger")
  val ios: Source.Value = Value("ios")
  val android: Source.Value = Value("android")
  val settlement: Source.Value = Value("settlement")
  val system: Source.Value = Value("system")
  val web: Source.Value = Value("web")
  val api: Source.Value = Value("api")
  val m: Source.Value = Value("m")
  val risk: Source.Value = Value("risk")

}
