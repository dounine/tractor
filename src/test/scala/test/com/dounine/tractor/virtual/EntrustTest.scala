package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, KillSwitches, SystemMaterializer}
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
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class EntrustTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25520
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[EntrustTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[EntrustTest].getSimpleName}"
       |""".stripMargin)
    .withFallback(
      ConfigFactory.parseResources("application-test.conf")
    )
    .resolve()
) with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)
  val portGlobal = new AtomicInteger(8200)
  val orderIdGlobal = new AtomicInteger(1)
  val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val dataMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(file"/tmp/journal_${classOf[EntrustTest].getSimpleName}", file"/tmp/snapshot_${classOf[EntrustTest].getSimpleName}")
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

  "entrust behavior" should {
    "run" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )
      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, EntrustBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort))
      LoggingTestKit.info(
        classOf[EntrustBase.RunSelfOk].getSimpleName
      )
        .expect(
          entrustBehavior.tell(EntrustBase.Run(socketPort))
        )

    }

    "create and trigger" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, EntrustBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort))
      entrustBehavior.tell(EntrustBase.Run(socketPort))

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(EntrustBase.Create(
        orderId = orderId,
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        price = 100,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(EntrustBase.CreateOk(orderId))

      val triggerMessage = MarketTradeModel.WsPrice(
        ch = s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
        tick = MarketTradeModel.WsTick(
          id = 123L,
          ts = System.currentTimeMillis(),
          data = Seq(
            MarketTradeModel.WsData(
              amount = 1,
              direction = Direction.buy,
              id = 123L,
              price = 100,
              ts = System.currentTimeMillis()
            )
          )
        ),
        ts = System.currentTimeMillis()
      ).toJson

      LoggingTestKit
        .info(classOf[EntrustBase.Entrusts].getSimpleName)
        .expect {
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        }
    }

    "create and cancel" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, EntrustBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort))
      entrustBehavior.tell(EntrustBase.Run(socketPort))

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(EntrustBase.Create(
        orderId = orderId,
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        price = 100,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(EntrustBase.CreateOk(orderId))

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(EntrustBase.CancelOk(orderId))
    }

    "create and multi cancel canceled fail" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, EntrustBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort))
      entrustBehavior.tell(EntrustBase.Run(socketPort))

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(EntrustBase.Create(
        orderId = orderId,
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        price = 100,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(EntrustBase.CreateOk(orderId))

      entrustBehavior.tell(EntrustBase.Cancel(orderId)(testKit.createTestProbe[BaseSerializer]().ref))
      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(EntrustBase.CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyCanceled))

    }

    "create and multi cancel match fail" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, EntrustBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort))
      entrustBehavior.tell(EntrustBase.Run(socketPort))

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(EntrustBase.Create(
        orderId = orderId,
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        price = 100,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(EntrustBase.CreateOk(orderId))

      val triggerMessage = MarketTradeModel.WsPrice(
        ch = s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
        tick = MarketTradeModel.WsTick(
          id = 123L,
          ts = System.currentTimeMillis(),
          data = Seq(
            MarketTradeModel.WsData(
              amount = 1,
              direction = Direction.buy,
              id = 123L,
              price = 100,
              ts = System.currentTimeMillis()
            )
          )
        ),
        ts = System.currentTimeMillis()
      ).toJson

      socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))

      TimeUnit.MILLISECONDS.sleep(50)

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(EntrustBase.CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyMatchAll))

    }
  }


}
