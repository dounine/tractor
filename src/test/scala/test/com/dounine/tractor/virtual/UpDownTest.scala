package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{BoundedSourceQueue, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.updown.{UpDownBase, UpDownBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.{EntrustBase, EntrustBehavior}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{PositionBase, PositionBehavior}
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BalanceModel, BaseSerializer, MarketTradeModel, NotifyModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.service.BalanceRepository
import com.dounine.tractor.tools.json.JsonParse
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, _}

class UpDownTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
       |akka.remote.artery.canonical.port = 25520
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          UpDownTest
        ].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          UpDownTest
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
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)
  val portGlobal = new AtomicInteger(8200)
  val orderIdGlobal = new AtomicInteger(1)
  val pingMessage = (time: Option[Long]) =>
    Await.result(
      Source
        .single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""")
        .map(ByteString(_))
        .via(Compression.gzip)
        .runWith(Sink.head),
      Duration.Inf
    )
  val dataMessage = (data: String) =>
    Await.result(
      Source
        .single(data)
        .map(ByteString(_))
        .via(Compression.gzip)
        .runWith(Sink.head),
      Duration.Inf
    )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(
      file"/tmp/journal_${classOf[UpDownTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[UpDownTest].getSimpleName}"
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
        typeKey = MarketTradeBehavior.typeKey
      )(
        createBehavior = entityContext => MarketTradeBehavior()
      )
    )
    sharding.init(
      Entity(
        typeKey = AggregationBehavior.typeKey
      )(
        createBehavior = entityContext => AggregationBehavior()
      )
    )

    sharding.init(
      Entity(
        typeKey = PositionBase.typeKey
      )(
        createBehavior = entityContext =>
          PositionBehavior(
            PersistenceId.of(
              PositionBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = TriggerBase.typeKey
      )(
        createBehavior = entityContext =>
          TriggerBehavior(
            PersistenceId.of(
              TriggerBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = EntrustNotifyBehavior.typeKey
      )(
        createBehavior = entityContext => EntrustNotifyBehavior()
      )
    )

    sharding.init(
      Entity(
        typeKey = EntrustBase.typeKey
      )(
        createBehavior = entityContext =>
          EntrustBehavior(
            PersistenceId.of(
              EntrustBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = UpDownBase.typeKey
      )(
        createBehavior = entityContext =>
          UpDownBehavior(
            PersistenceId.of(
              UpDownBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

  }

  def createSocket(): (BoundedSourceQueue[Message], String) = {
    val socketPort = portGlobal.incrementAndGet()
    val (
      socketClient: BoundedSourceQueue[Message],
      source: Source[Message, NotUsed]
    ) = Source
      .queue[Message](10)
      .preMaterialize()
    val result = Flow.fromSinkAndSourceCoupledMat(
      sink = Flow[Message].to(Sink.ignore),
      source = source
    )(Keep.right)

    Await.result(
      Http(system)
        .newServerAt("0.0.0.0", socketPort)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext),
      Duration.Inf
    )

    (socketClient, socketPort.toString)
  }

  final val phone = "123456789"
  final val symbol = CoinSymbol.BTC
  final val contractType = ContractType.quarter
  final val direction = Direction.buy

  "updown behavior" should {
    "run" in {
      val (socketClient, socketPort) = createSocket()
      val mockBalanceService = mock[BalanceRepository]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(1.0),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      when(mockBalanceService.mergeBalance(any, any, any)) thenAnswer (args =>
        Future(
          Option(
            BigDecimal(1.0)
          )
        )(system.executionContext)
      )
      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustId = EntrustBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val entrustBehavior =
        sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val triggerId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val triggerBehavior =
        sharding.entityRefFor(TriggerBase.typeKey, triggerId)

      triggerBehavior.tell(
        TriggerBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val updownId = UpDownBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

      LoggingTestKit
        .info(
          classOf[UpDownBase.Trigger].getName
        )
        .withOccurrences(2)
        .expect(
          updownBehavior.tell(
            UpDownBase.Run(
              marketTradeId = socketPort,
              entrustId = entrustId,
              triggerId = triggerId,
              entrustNotifyId = socketPort
            )
          )
        )

    }

    "run and create trigger" in {
      val (socketClient, socketPort) = createSocket()
      val mockBalanceService = mock[BalanceRepository]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(1.0),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      when(mockBalanceService.mergeBalance(any, any, any)) thenAnswer (args =>
        Future(
          Option(
            BigDecimal(1.0)
          )
        )(system.executionContext)
      )

      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustId = EntrustBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val entrustBehavior =
        sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val triggerId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val triggerBehavior =
        sharding.entityRefFor(TriggerBase.typeKey, triggerId)

      triggerBehavior.tell(
        TriggerBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val updownId = UpDownBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

      val triggerMessage = MarketTradeModel
        .WsPrice(
          ch =
            s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
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
        )
        .toJson

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )

      val updateProbe = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(
        UpDownBase.Update(
          UpDownUpdateType.openReboundPrice,
          BigDecimal(1.0),
          updateProbe.ref
        )
      )

      LoggingTestKit
        .info(
          classOf[TriggerBase.CreateOk].getName
        )
        .expect(
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(triggerMessage)
            )
          )
        )

    }

    "create trigger and fireTrigger fail" in {
      val (socketClient, socketPort) = createSocket()
      val mockBalanceService = mock[BalanceRepository]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(1.0),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      when(mockBalanceService.mergeBalance(any, any, any)) thenAnswer (args =>
        Future(
          Option(
            BigDecimal(1.0)
          )
        )(system.executionContext)
      )

      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustId = EntrustBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val entrustBehavior =
        sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val triggerId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val triggerBehavior =
        sharding.entityRefFor(TriggerBase.typeKey, triggerId)

      triggerBehavior.tell(
        TriggerBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val updownId = UpDownBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

      val triggerMessage = MarketTradeModel
        .WsPrice(
          ch =
            s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
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
        )
        .toJson

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )

      val updateProbe = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(
        UpDownBase.Update(
          UpDownUpdateType.openScheduling,
          1.seconds,
          updateProbe.ref
        )
      )
      updateProbe.expectMessage(UpDownBase.UpdateOk())

      LoggingTestKit
        .info(
          classOf[TriggerBase.Create].getName
        )
        .expect(
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(triggerMessage)
            )
          )
        )

      LoggingTestKit
        .error(
          classOf[TriggerBase.CreateFail].getName
        )
        .expect {
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(triggerMessage)
            )
          )
        }

    }

    "run and opened" in {
      val (socketClient, socketPort) = createSocket()
      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val mockBalanceService = mock[BalanceRepository]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(1.0),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      when(mockBalanceService.mergeBalance(any, any, any)) thenAnswer (args =>
        Future(
          Option(
            BigDecimal(1.0)
          )
        )(system.executionContext)
      )

      ServiceSingleton.put(classOf[BalanceRepository], mockBalanceService)
      val positionId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustId = EntrustBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val entrustBehavior =
        sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val triggerId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val triggerBehavior =
        sharding.entityRefFor(TriggerBase.typeKey, triggerId)

      triggerBehavior.tell(
        TriggerBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val updownId = UpDownBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )

      val triggerMessage = MarketTradeModel.WsPrice(
        ch =
          s"market.${CoinSymbol.BTC}_${ContractType.getAlias(ContractType.quarter)}",
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
      )

      val updateProbe = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(
        UpDownBase.Update(
          UpDownUpdateType.openReboundPrice,
          BigDecimal(1.0),
          updateProbe.ref
        )
      )
      updateProbe.expectMessage(UpDownBase.UpdateOk())

      LoggingTestKit
        .info(
          classOf[TriggerBase.CreateOk].getName
        )
        .expect(
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(triggerMessage.toJson)
            )
          )
        )

      val updateCloseProbe = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(
        UpDownBase.Update(
          UpDownUpdateType.closeReboundPrice,
          BigDecimal(1.0),
          updateCloseProbe.ref
        )
      )
      updateCloseProbe.expectMessage(UpDownBase.UpdateOk())

      LoggingTestKit
        .info(
          classOf[TriggerBase.TriggerOk].getName
        )
        .expect {
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(
                triggerMessage
                  .copy(
                    tick = triggerMessage.tick.copy(
                      data = Seq(
                        triggerMessage.tick.data.head.copy(
                          price = 104
                        )
                      )
                    )
                  )
                  .toJson
              )
            )
          )
        }

      LoggingTestKit
        .info(
          classOf[EntrustNotifyBehavior.Receive].getName
        )
        .expect {
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(
                triggerMessage
                  .copy(
                    tick = triggerMessage.tick.copy(
                      data = Seq(
                        triggerMessage.tick.data.head.copy(
                          price = 103
                        )
                      )
                    )
                  )
                  .toJson
              )
            )
          )
        }

      LoggingTestKit
        .info(
          classOf[EntrustNotifyBehavior.Receive].getName
        )
        .expect {
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(
                triggerMessage
                  .copy(
                    tick = triggerMessage.tick.copy(
                      data = Seq(
                        triggerMessage.tick.data.head.copy(
                          price = 102
                        )
                      )
                    )
                  )
                  .toJson
              )
            )
          )
        }

      LoggingTestKit
        .info(
          classOf[EntrustNotifyBehavior.Receive].getName
        )
        .expect {
          socketClient.offer(
            BinaryMessage.Strict(
              dataMessage(
                triggerMessage
                  .copy(
                    tick = triggerMessage.tick.copy(
                      data = Seq(
                        triggerMessage.tick.data.head.copy(
                          price = 102
                        )
                      )
                    )
                  )
                  .toJson
              )
            )
          )
        }

      val queryStatus = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(UpDownBase.Query()(queryStatus.ref))
      val querySuccess =
        queryStatus.receiveMessage().asInstanceOf[UpDownBase.QuerySuccess]
      querySuccess.status shouldBe UpDownStatus.Closed

    }

  }

}
