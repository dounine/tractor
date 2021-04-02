package test.com.dounine.tractor.behavior

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import com.dounine.tractor.behaviors.socket.SocketBehavior
import com.dounine.tractor.model.models.{BaseSerializer, UserModel}
import com.dounine.tractor.model.types.service.UserStatus
import com.dounine.tractor.service.{UserApi, UserService}
import com.dounine.tractor.tools.json.JsonParse
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future
import scala.util.Try

class SocketBehaviorTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          SocketBehaviorTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          SocketBehaviorTest
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

  "socket behavior test" should {

    "logined" in {
      val userApi = mock[UserApi]
      val phone = "123456789"
      when(
        userApi.login(phone, Map.empty)
      ).thenAnswer(args => {
        val _phone = args.getArgument[String](0)
        val _data = args.getArgument[Map[String, String]](1)
        implicit val clock: Clock = Clock.systemUTC
        val claim: UserModel.Session =
          UserModel.Session(
            phone = _phone,
            data = Option(_data)
          )
        val time = System.currentTimeMillis() / 1000
        Jwt.encode(
          JwtHeader(JwtAlgorithm.HS256),
          JwtClaim(claim.toJson)
            .issuedAt(time)
            .expiresIn(60),
          "admin"
        )
      })
      val loginToken = userApi.login(phone, Map.empty)
      when(userApi.parse(loginToken))
        .thenAnswer(args => {
          val token = args.getArgument[String](0)
          if (Jwt.isValid(token.trim(), "admin", Seq(JwtAlgorithm.HS256))) {
            val result: Try[(String, String, String)] =
              Jwt.decodeRawAll(
                token.trim(),
                "admin",
                Seq(JwtAlgorithm.HS256)
              )
            Option(result.get._2.jsonTo[UserModel.Session])
          } else Option.empty
        })
      ServiceSingleton.put(classOf[UserApi], userApi)
      val socketBehavior = system.systemActorOf(SocketBehavior(), "socket")
      val client = testKit.createTestProbe[BaseSerializer]()
      socketBehavior.tell(
        SocketBehavior.Login(
          loginToken,
          client = client.ref
        )
      )
      client.expectMessage(SocketBehavior.LoginOk())
    }

  }
}
