package test.com.dounine.tractor.service

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import com.dounine.tractor.model.models.UserModel
import com.dounine.tractor.model.types.service.UserStatus
import com.dounine.tractor.service.UserService
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future
import scala.util.Try

class UserServiceTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          UserServiceTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          UserServiceTest
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

  "user service test" should {

    val userService = new UserService(system)
    val phone = "123456789"
    "token create and parse" in {
      val token = userService.login(phone, Map.empty)
      val result = userService.parse(token)
      result.get.phone shouldBe phone
    }

    "token create and parse fail" in {
      val token = userService.login(phone, Map.empty)
      val result = userService.parse(token + "fail")
      result shouldBe Option.empty
    }

    "empty info" in {
      userService.info(phone).futureValue shouldBe Option.empty
    }

    "exit info" in {
      val phone = "12345678911"
      userService.add(UserModel.UserInfo(
        phone = phone,
        password = "admin",
        status = UserStatus.normal,
        createTime = LocalDateTime.now()
      )).futureValue shouldBe Option(1)
      userService.delete(phone).futureValue shouldBe 1
    }

  }
}
