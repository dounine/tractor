package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.{Cluster, Join}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import com.dounine.tractor.model.models.{BalanceModel, BaseSerializer}
import com.dounine.tractor.model.types.currency.CoinSymbol
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.service.virtual.BalanceRepository
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object RemoteActor2 extends ActorSerializerSuport {
  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("RemoteActor2")

  trait Event extends BaseSerializer
  case class Update(phone: String, symbol: CoinSymbol, value: BigDecimal)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Event
  case class UpdateOk(value: Option[BigDecimal]) extends Event
  def apply(): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val materializer = SystemMaterializer(context.system).materializer
        Behaviors.receiveMessage {
          case e @ Update(phone, symbol, value) => {
            context.log.info(e.logJson)
            Source
              .future(
                ServiceSingleton
                  .get(classOf[BalanceRepository])
                  .mergeBalance(
                    phone,
                    symbol,
                    value
                  )
              )
              .runForeach(value => {
                e.replyTo.tell(UpdateOk(value))
              })(materializer)
            Behaviors.same
          }
        }
      }
    }
}

class MockTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
       |akka.remote.artery.canonical.port = 25520
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          MockTest
        ].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          MockTest
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
    with MockitoSugar {
  implicit val ec = system.executionContext

  val sharding = ClusterSharding(system)
  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(
      file"/tmp/journal_${classOf[MockTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[MockTest].getSimpleName}"
    )
    try {
      files.filter(_.exists).foreach(_.delete())
    } catch {
      case e =>
    }

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(
      Entity(
        typeKey = RemoteActor2.typeKey
      )(
        createBehavior = entityContext => RemoteActor2()
      )
    )
  }

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

    "bigdecimal" in {
      val remoteActor = sharding.entityRefFor(RemoteActor2.typeKey, "hello")
      val mockBalanceService = mock[BalanceRepository]
      val price = BigDecimal(-0.0047572815533979754)

      doAnswer(_ => {
        Future(Option(BigDecimal(1.0)))
      }).when(
          mockBalanceService
        )
        .balance(any, any)
      when(
        mockBalanceService.mergeBalance("123", CoinSymbol.BTC, BigDecimal(1.0))
      ).thenReturn(
        Future(
          Option(BigDecimal(1.0))
        )
      )
      when(mockBalanceService.mergeBalance("123", CoinSymbol.BTC, price))
        .thenReturn(
          Future(
            Option(BigDecimal(1.0))
          )
        )

      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)

      val updateProbe1 = testKit.createTestProbe[BaseSerializer]()
      remoteActor.tell(
        RemoteActor2.Update(
          "123",
          CoinSymbol.BTC,
          BigDecimal(-0.0047572815533979754)
        )(updateProbe1.ref)
      )
      updateProbe1.expectMessage(RemoteActor2.UpdateOk(Option(BigDecimal(1.0))))

      val updateProbe2 = testKit.createTestProbe[BaseSerializer]()
      remoteActor.tell(
        RemoteActor2.Update("123", CoinSymbol.BTC, BigDecimal(1.0))(
          updateProbe2.ref
        )
      )
      updateProbe2.expectMessage(RemoteActor2.UpdateOk(Option(BigDecimal(1.0))))

      info(
        (BigDecimal("-0.0047572815533979754") == BigDecimal(
          -0.0047572815533979754f
        )).toString
      )

      info(BigDecimal("-0.0047572815533979754").toString())

    }
  }

}
