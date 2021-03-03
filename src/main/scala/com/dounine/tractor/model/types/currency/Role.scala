package com.dounine.tractor.model.types.currency

object Role extends Enumeration {
  type Role = Value

  val taker: Role.Value = Value("taker")
  val maker: Role.Value = Value("maker")
}
