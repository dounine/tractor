package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LoggingTestKit, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.{EntrustBase, EntrustBehavior}
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class TriggerAndEntrustTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25522
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[TriggerAndEntrustTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[TriggerAndEntrustTest].getSimpleName}"
       |""".stripMargin)
    .withFallback(
      ConfigFactory.parseResources("application-test.conf")
    )
    .resolve()
) with Matchers with AnyWordSpecLike with JsonParse {
  val portGlobal = new AtomicInteger(8200)
  val orderIdGlobal = new AtomicInteger(1)
  val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val dataMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(file"/tmp/journal_${classOf[TriggerAndEntrustTest].getSimpleName}", file"/tmp/snapshot_${classOf[TriggerAndEntrustTest].getSimpleName}")
    try {
      files.filter(_.exists).foreach(_.delete())
    } catch {
      case e =>
    }

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(Entity(
      typeKey = MarketTradeBehavior.typeKey
    )(
      createBehavior = entityContext => MarketTradeBehavior()
    ))
    sharding.init(Entity(
      typeKey = EntrustBase.typeKey
    )(
      createBehavior = entityContext => EntrustBehavior(
        PersistenceId.of(
          EntrustBase.typeKey.name,
          entityContext.entityId
        ),
        entityContext.shard
      )
    ))

    sharding.init(Entity(
      typeKey = TriggerBase.typeKey
    )(
      createBehavior = entityContext => TriggerBehavior(
        PersistenceId.of(
          TriggerBase.typeKey.name,
          entityContext.entityId
        ),
        entityContext.shard
      )
    ))
  }

  def createSocket(): (BoundedSourceQueue[Message], String) = {
    val socketPort = portGlobal.incrementAndGet()
    val (socketClient: BoundedSourceQueue[Message], source: Source[Message, NotUsed]) = Source.queue[Message](10)
      .preMaterialize()
    val result = Flow.fromSinkAndSourceCoupledMat(
      sink = Flow[Message].to(Sink.ignore),
      source = source
    )(Keep.right)

    Await.result(Http(system)
      .newServerAt("0.0.0.0", socketPort)
      .bindFlow(handleWebSocketMessages(result))
      .andThen(_.get)(system.executionContext), Duration.Inf)

    (socketClient, socketPort.toString)
  }

  "trigger and entrust" should {

    "create trigger and entrust" in {
      val (socketClient, socketPort) = createSocket()
      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val entrustId = EntrustBase.createEntityId(
        phone = "123456789", symbol = CoinSymbol.BTC, contractType = ContractType.quarter, socketPort
      )
      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      entrustBehavior.tell(EntrustBase.Run(
        marketTradeId = socketPort
      ))

      val triggerId = TriggerBase.createEntityId("123456789", CoinSymbol.BTC, ContractType.quarter, socketPort)
      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, triggerId)
      triggerBehavior.tell(TriggerBase.Run(marketTradeId = socketPort, entrustId = entrustId))

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.incrementAndGet().toString
      triggerBehavior.tell(TriggerBase.Create(
        orderId = orderId,
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        triggerType = TriggerType.ge,
        orderPrice = 100,
        triggerPrice = 90,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(TriggerBase.CreateOk(orderId))

      val triggerMessage = MarketTradeModel.WsPrice(
        ch = s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
        tick = MarketTradeModel.WsTick(
          id = 3L,
          ts = System.currentTimeMillis(),
          data = Seq(
            MarketTradeModel.WsData(
              amount = 1,
              direction = Direction.buy,
              id = 123L,
              price = 91,
              ts = System.currentTimeMillis()
            )
          )
        ),
        ts = System.currentTimeMillis()
      ).toJson

      LoggingTestKit
        .info(classOf[EntrustBase.Create].getName)
        .expect {
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        }

      LoggingTestKit
        .info(classOf[EntrustBase.Entrusts].getName)
        .expect {
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        }


    }

  }


}
