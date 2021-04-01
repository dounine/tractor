package com.dounine.tractor.service

import com.dounine.tractor.model.models.{BalanceModel, UserModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol

import scala.concurrent.Future

trait UserApi {

  def parse(token: String): Option[UserModel.Session]

  def info(phone: String): Future[Option[UserModel.UserInfo]]

  def login(phone: String, data: Map[String, String]): String

  def add(info: UserModel.UserInfo): Future[Option[Int]]

  def delete(phone: String): Future[Int]

}
