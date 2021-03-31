package com.dounine.tractor.service

import akka.actor.typed.ActorSystem
import com.dounine.tractor.model.models.UserModel
import com.dounine.tractor.store.UserTable
import com.dounine.tractor.tools.akka.db.DataSource
import com.dounine.tractor.tools.json.JsonParse
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import java.time.Clock
import scala.concurrent.Future
import scala.util.Try

class UserService(system: ActorSystem[_])
    extends UserRepository
    with JsonParse {

  private final val jwtSecret =
    system.settings.config.getConfig("app").getString("jwt.secret")
  private final val jwtExpire =
    system.settings.config.getConfig("app").getDuration("jwt.expire").getSeconds
  private val db = DataSource(system).source().db
  private val dict: TableQuery[UserTable] = TableQuery[UserTable]

  override def parse(token: String): Option[UserModel.Session] = {
    if (Jwt.isValid(token.trim(), jwtSecret, Seq(JwtAlgorithm.HS256))) {
      val result: Try[(String, String, String)] =
        Jwt.decodeRawAll(
          token.trim(),
          jwtSecret,
          Seq(JwtAlgorithm.HS256)
        )
      Option(result.get._2.jsonTo[UserModel.Session])
    } else Option.empty
  }

  override def info(phone: String): Future[Option[UserModel.UserInfo]] = {
    db.run(
      dict.filter(_.phone === phone).result.headOption
    )
  }

  override def login(phone: String, data: Map[String, String]): String = {
    implicit val clock: Clock = Clock.systemUTC
    val claim: UserModel.Session =
      UserModel.Session(phone = phone)
    val time = System.currentTimeMillis() / 1000
    Jwt.encode(
      JwtHeader(JwtAlgorithm.HS256),
      JwtClaim(claim.toJson)
        .issuedAt(time)
        .expiresIn(jwtExpire),
      jwtSecret
    )
  }

  override def add(info: UserModel.UserInfo): Future[Option[Int]] = {
    db.run(
      dict ++= Seq(info)
    )
  }

  override def delete(phone: String): Future[Int] = {
    db.run(
      dict.filter(_.phone === phone).delete
    )
  }

}
