package com.dounine.tractor.model.types.service

object UserStatus extends Enumeration {
  type UserStatus = Value
  val normal: UserStatus.Value = Value("normal")
  val locked: UserStatus.Value = Value("locked")
  val inactive: UserStatus.Value = Value("inactive")
}
