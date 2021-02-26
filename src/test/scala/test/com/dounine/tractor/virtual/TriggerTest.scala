package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.{BoundedSourceQueue, KillSwitches, SystemMaterializer}
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction, LeverRate, Offset, OrderPriceType, TriggerCancelFailStatus, TriggerType}
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class TriggerTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25521
       |akka.persistence.journal.leveldb.dir = "/tmp/journal/${classOf[TriggerTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot/${classOf[TriggerTest].getSimpleName}"
       |""".stripMargin)
    .withFallback(
      ConfigFactory.parseResources("application-test.conf")
    )
    .resolve()
) with Matchers with AnyWordSpecLike with JsonParse {
  val portGlobal = new AtomicInteger(8100)
  val orderIdGlobal = new AtomicInteger(1)
  val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val dataMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(file"/tmp/journal/${classOf[TriggerTest].getSimpleName}", file"/tmp/snapshot/${classOf[TriggerTest].getSimpleName}")
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


  "trigger behavior" should {
    "run" in {
      val socketPort = portGlobal.incrementAndGet()
      val time = System.currentTimeMillis()
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

      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort.toString)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort.toString))
      LoggingTestKit.info(
        classOf[TriggerBase.RunSelfOk].getSimpleName
      )
        .expect(
          triggerBehavior.tell(TriggerBase.Run(socketPort.toString))
        )

    }

    "create and trigger" in {
      val socketPort = portGlobal.incrementAndGet()
      val time = System.currentTimeMillis()
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

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort.toString)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort.toString))
      triggerBehavior.tell(TriggerBase.Run(socketPort.toString))

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
          id = 123L,
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
        .info(classOf[TriggerBase.Triggers].getSimpleName)
        .expect {
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        }

    }

    "create and cancel" in {
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

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort.toString)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort.toString))
      triggerBehavior.tell(TriggerBase.Run(socketPort.toString))

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

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      triggerBehavior.tell(TriggerBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(TriggerBase.CancelOk(orderId))
    }

    "create and multi cancel canceled fail" in {
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

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort.toString)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort.toString))
      triggerBehavior.tell(TriggerBase.Run(socketPort.toString))

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

      triggerBehavior.tell(TriggerBase.Cancel(orderId)(testKit.createTestProbe[BaseSerializer]().ref))
      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      triggerBehavior.tell(TriggerBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(TriggerBase.CancelFail(orderId, TriggerCancelFailStatus.cancelAlreadyCanceled))
    }

    "create and multi cancel match fail" in {
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

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort.toString)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter, socketPort.toString))
      triggerBehavior.tell(TriggerBase.Run(socketPort.toString))

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

      socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))

      TimeUnit.MILLISECONDS.sleep(50)

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      triggerBehavior.tell(TriggerBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(TriggerBase.CancelFail(orderId, TriggerCancelFailStatus.cancelAlreadyMatched))

    }

  }


}
