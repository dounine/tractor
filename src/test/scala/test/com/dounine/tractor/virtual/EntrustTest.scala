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
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{BoundedSourceQueue, KillSwitches, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.{EntrustBase, EntrustBehavior}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{PositionBase, PositionBehavior}
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BalanceModel, BaseSerializer, MarketTradeModel, NotifyModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.service.BalanceApi
import com.dounine.tractor.tools.json.JsonParse
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class EntrustTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
       |akka.remote.artery.canonical.port = 25520
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          EntrustTest
        ].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          EntrustTest
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
      file"/tmp/journal_${classOf[EntrustTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[EntrustTest].getSimpleName}"
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
        typeKey = EntrustNotifyBehavior.typeKey
      )(
        createBehavior = entityContext => EntrustNotifyBehavior()
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

  "entrust behavior" should {
    "run" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )
      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      LoggingTestKit
        .info(
          classOf[EntrustBase.RunSelfOk].getName
        )
        .expect(
          entrustBehavior.tell(
            EntrustBase.Run(
              marketTradeId = socketPort,
              positionId = "",
              entrustNotifyId = "",
              aggregationId = socketPort,
              contractSize = 100
            )
          )
        )

    }

    "create and trigger" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )

      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(createProbe.ref)
      )

      createProbe.expectMessageType[EntrustBase.CreateOk]

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

      LoggingTestKit
        .info(classOf[EntrustBase.EntrustOk].getName)
        .expect {
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        }
    }

    "create and cancel" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )

      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(createProbe.ref)
      )

      createProbe.expectMessageType[EntrustBase.CreateOk]

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(EntrustBase.CancelOk(orderId))
    }

    "create and multi cancel canceled fail" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(createProbe.ref)
      )

      createProbe.expectMessageType[EntrustBase.CreateOk]

      entrustBehavior.tell(
        EntrustBase
          .Cancel(orderId)(testKit.createTestProbe[BaseSerializer]().ref)
      )
      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(
        EntrustBase
          .CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyCanceled)
      )

    }

    "create and multi cancel match fail" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()

      val mockBalanceService = mock[BalanceApi]
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

      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(createProbe.ref)
      )

      createProbe.expectMessageType[EntrustBase.CreateOk]

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

      LoggingTestKit
        .info(
          classOf[EntrustBase.EntrustOk].getName
        )
        .expect(
          socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))
        )

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(
        EntrustBase
          .CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyMatchAll)
      )

    }

    "leverRate fun test" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(createProbe.ref)
      )

      createProbe.expectMessageType[EntrustBase.CreateOk]

      val leverRateProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(
        EntrustBase.IsCanChangeLeverRate()(leverRateProbe.ref)
      )
      leverRateProbe.expectMessage(EntrustBase.ChangeLeverRateNo())

      val cancelProbe = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(EntrustBase.Cancel(orderId)(cancelProbe.ref))
      cancelProbe.expectMessage(EntrustBase.CancelOk(orderId))

      val leverRateProbeYes = testKit.createTestProbe[BaseSerializer]()
      entrustBehavior.tell(
        EntrustBase.IsCanChangeLeverRate()(leverRateProbeYes.ref)
      )
      leverRateProbeYes.expectMessage(EntrustBase.ChangeLeverRateYes())

    }

    "notify test" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val mockBalanceService = mock[BalanceApi]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(1),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustNotifyBehavior =
        sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      LoggingTestKit
        .info(
          classOf[EntrustNotifyBehavior.Push].getName
        )
        .expect(
          entrustBehavior.tell(
            EntrustBase.Create(
              orderId = orderId,
              offset = Offset.open,
              orderPriceType = OrderPriceType.limit,
              price = 100,
              volume = 1
            )(createProbe.ref)
          )
        )

      val subProbe = testKit.createTestProbe[BaseSerializer]()
      entrustNotifyBehavior.tell(
        EntrustNotifyBehavior.Sub(
          CoinSymbol.BTC,
          ContractType.quarter,
          Direction.buy
        )(subProbe.ref)
      )
      val subResponse = subProbe
        .receiveMessage(3.seconds)
        .asInstanceOf[EntrustNotifyBehavior.SubOk]
      val notifyInfo = subResponse.source
        .runWith(TestSink[EntrustNotifyBehavior.Receive])
        .request(1)
        .expectNext(3.seconds)
      notifyInfo.notif.orderId shouldBe orderId
    }

    "create order size overflow" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val mockBalanceService = mock[BalanceApi]
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
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustNotifyBehavior =
        sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      (0 to 10).foreach(_ => {
        val orderId = orderIdGlobal.getAndIncrement().toString
        entrustBehavior.tell(
          EntrustBase.Create(
            orderId = orderId,
            offset = Offset.open,
            orderPriceType = OrderPriceType.limit,
            price = 100,
            volume = 1
          )(createProbe.ref)
        )
      })

      val overflowProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      entrustBehavior.tell(
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 100,
          volume = 1
        )(overflowProbe.ref)
      )
      overflowProbe.expectMessageType[EntrustBase.CreateFail]

    }

    "create order createAvailableGuaranteeIsInsufficient" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val positionId = PositionBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)
      val aggregationBehavior =
        sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustNotifyBehavior =
        sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val entrustBehavior = sharding.entityRefFor(
        EntrustBase.typeKey,
        EntrustBase.createEntityId(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          randomId = socketPort
        )
      )
      val mockBalanceService = mock[BalanceApi]
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal(0.001),
              createTime = LocalDateTime.now()
            )
          )
        )(system.executionContext)
      )
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = socketPort,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      val orderId = orderIdGlobal.getAndIncrement().toString
      val createRequest =
        EntrustBase.Create(
          orderId = orderId,
          offset = Offset.open,
          orderPriceType = OrderPriceType.limit,
          price = 10000,
          volume = 3
        )(createProbe.ref)
      entrustBehavior.tell(
        createRequest
      )
      createProbe.expectMessage(
        EntrustBase.CreateFail(
          createRequest,
          EntrustCreateFailStatus.createAvailableGuaranteeIsInsufficient
        )
      )

    }

  }

}
