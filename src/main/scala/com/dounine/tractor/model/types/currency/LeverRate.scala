package com.dounine.tractor.model.types.currency

object LeverRate extends Enumeration {
  type LeverRate = Value
  val x1: LeverRate.Value = Value("1")
  val x2: LeverRate.Value = Value("2")
  val x3: LeverRate.Value = Value("3")
  val x5: LeverRate.Value = Value("5")
  val x10: LeverRate.Value = Value("10")
  val x20: LeverRate.Value = Value("20")
  val x30: LeverRate.Value = Value("30")
  val x50: LeverRate.Value = Value("50")
  val x75: LeverRate.Value = Value("75")
  val x100: LeverRate.Value = Value("100")
  val x125: LeverRate.Value = Value("125")

  val dbLength: Int = 3
}
