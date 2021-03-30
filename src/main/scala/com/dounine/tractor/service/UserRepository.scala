package com.dounine.tractor.service

import com.dounine.tractor.model.models.{BalanceModel, UserModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol

import scala.concurrent.Future

trait UserRepository {

  def valid(token: String): Option[UserModel.Session]

  def info(phone: String): Future[Option[UserModel.UserInfo]]

  def login(phone: String, data: Map[String, String]): String

}
