package test.com.dounine.tractor.store

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import com.dounine.tractor.model.models.UserModel
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.time.Clock
import scala.util.Try

class UserRepositoryTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          UserRepositoryTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          UserRepositoryTest
        ].getSimpleName}"
                      |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with MockitoSugar
    with JsonParse {

  "user repository test" should {

    val phone = "123456789"
    "token valid" in {
      val jwtSecretStr = "admin123"

      implicit val clock: Clock = Clock.systemUTC
      val claim: UserModel.Session =
        UserModel.Session(phone = phone)

      val time = System.currentTimeMillis() / 1000
      val token: String = Jwt.encode(
        JwtHeader(JwtAlgorithm.HS256),
        JwtClaim(claim.toJson)
          .issuedAt(time)
          .expiresIn(60),
        jwtSecretStr
      )

      val result =
        if (Jwt.isValid(token.trim(), jwtSecretStr, Seq(JwtAlgorithm.HS256))) {
          val result: Try[(String, String, String)] =
            Jwt.decodeRawAll(
              token.trim(),
              jwtSecretStr,
              Seq(JwtAlgorithm.HS256)
            )
          Option(result.get._2.jsonTo[UserModel.Session])
        } else Option.empty

      result shouldBe Option(
        UserModel.Session(
          phone = phone,
          data = None,
          iat = Option(time),
          exp = Option(time + 60)
        )
      )
    }

    "token invalid" in {
      val jwtSecretStr = "admin123"

      implicit val clock: Clock = Clock.systemUTC
      val claim: UserModel.Session =
        UserModel.Session(phone = phone)

      val time = System.currentTimeMillis() / 1000
      val token: String = Jwt.encode(
        JwtHeader(JwtAlgorithm.HS256),
        JwtClaim(claim.toJson)
          .issuedAt(time)
          .expiresIn(0),
        jwtSecretStr
      )

      val result =
        if (Jwt.isValid(token.trim(), jwtSecretStr, Seq(JwtAlgorithm.HS256))) {
          val result: Try[(String, String, String)] =
            Jwt.decodeRawAll(
              token.trim(),
              jwtSecretStr,
              Seq(JwtAlgorithm.HS256)
            )
          Option(result.get._2.jsonTo[UserModel.Session])
        } else Option.empty

      result shouldBe Option.empty

    }

  }
}
