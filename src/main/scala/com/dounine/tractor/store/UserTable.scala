package com.dounine.tractor.store

import com.dounine.tractor.model.models.UserModel
import com.dounine.tractor.model.types.service.UserStatus.UserStatus
import slick.lifted.{PrimaryKey, ProvenShape}
import slick.jdbc.MySQLProfile.api._

import java.time.LocalDateTime

class UserTable(tag: Tag)
    extends Table[UserModel.UserInfo](tag, _tableName = "tractor-user")
    with EnumMapper {

  override def * : ProvenShape[UserModel.UserInfo] =
    (
      phone,
      password,
      status,
      createTime
    ).mapTo[UserModel.UserInfo]

  def phone: Rep[String] = column[String]("phone", O.Length(13), O.PrimaryKey)

  def password: Rep[String] = column[String]("password", O.Length(20))

  def status: Rep[UserStatus] = column[UserStatus]("status", O.Length(10))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.Length(23))(localDateTime2timestamp)

}
