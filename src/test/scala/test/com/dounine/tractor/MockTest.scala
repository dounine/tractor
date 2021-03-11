package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ManualTime,
  ScalaTestWithActorTestKit
}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.model.models.BalanceModel
import com.dounine.tractor.model.types.currency.CoinSymbol
import com.dounine.tractor.service.virtual.BalanceRepository
import com.dounine.tractor.tools.util.ServiceSingleton
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class MockTest
    extends ScalaTestWithActorTestKit()
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with MockitoSugar {
  implicit val ec = system.executionContext
  "mock test" should {
    "hello mock" in {
      val mockBalanceService = mock[BalanceRepository]
      val nowTime = LocalDateTime.now()
      val balanceInfo = BalanceModel.Info(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        balance = 1.0,
        createTime = nowTime
      )
      when(mockBalanceService.balance("123456789", CoinSymbol.BTC)).thenReturn(
        Future(
          Option(
            balanceInfo
          )
        )
      )
      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)

      val result = Await.result(
        ServiceSingleton
          .get(classOf[BalanceRepository])
          .balance("123456789", CoinSymbol.BTC),
        Duration.Inf
      )

      result.shouldBe(Option(balanceInfo))

    }
  }

}
