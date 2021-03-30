package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.service.UserStatus.UserStatus

import java.time.LocalDateTime

object UserModel {

  final case class UserInfo(
      phone: String,
      password: String,
      status: UserStatus,
      createTime: LocalDateTime
  ) extends BaseSerializer

  final case class Session(
      phone: String,
      data: Option[Map[String, String]] = None,
      iat: Option[Long] = None, //issued time seconds
      exp: Option[Long] = None //expire time seconds
  ) extends BaseSerializer

}
