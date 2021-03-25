package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{
  LoggingTestKit,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.{
  EntrustBase,
  EntrustBehavior
}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{
  PositionBase,
  PositionBehavior
}
import com.dounine.tractor.behaviors.virtual.trigger.{
  TriggerBase,
  TriggerBehavior
}
import com.dounine.tractor.model.models.{
  BalanceModel,
  BaseSerializer,
  ContractAdjustfactorModel,
  MarketTradeModel
}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.service.virtual.BalanceRepository
import com.dounine.tractor.tools.json.JsonParse
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class PositionTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
       |akka.remote.artery.canonical.port = 25520
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          PositionTest
        ].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          PositionTest
        ].getSimpleName}"
       |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with JsonParse
    with MockitoSugar {
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
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(
      file"/tmp/journal_${classOf[PositionTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[PositionTest].getSimpleName}"
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
  }

  final val phone = "123456789"
  final val symbol = CoinSymbol.BTC
  final val contractType = ContractType.quarter
  final val direction = Direction.buy

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

  "position" should {

    "open" in {
      val (socketClient, socketPort) = createSocket()
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

      positionBehavior.tell(
        PositionBase.ReplaceData(
          data = PositionBase.DataStore(
            position = Option(
              PositionBase.PositionInfo(
                direction = direction,
                volume = 1,
                available = 1,
                frozen = 0,
                openFee = 0,
                closeFee = 0,
                costOpen = 100,
                costHold = 100,
                profitUnreal = 0,
                profitRate = 0,
                profit = 0,
                positionMargin = 0,
                createTime = LocalDateTime.now()
              )
            ),
            contractAdjustfactors = Seq.empty,
            config = PositionBase.Config(
              marketTradeId = socketPort
            ),
            price = 0,
            phone = phone,
            symbol = symbol,
            contractType = contractType,
            direction = direction,
            leverRate = LeverRate.x20,
            entityId = positionId,
            contractSize = 100
          )
        )
      )
      val closeNotAvaiable = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.Create(
          offset = Offset.close,
          volume = 2,
          latestPrice = 100
        )(closeNotAvaiable.ref)
      )
      closeNotAvaiable.expectMessage(
        PositionBase.CreateFail(
          PositionCreateFailStatus.createCloseNotEnoughIsAvailable
        )
      )

      val leverRateProbe = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.IsCanChangeLeverRate()(leverRateProbe.ref)
      )
      leverRateProbe.expectMessage(PositionBase.ChangeLeverRateNo())

      val mergeProbe = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.Create(
          offset = Offset.open,
          volume = 1,
          latestPrice = 100
        )(mergeProbe.ref)
      )
      mergeProbe.expectMessage(PositionBase.MergeOk())

      val mockBalanceService = mock[BalanceRepository]
      val nowTime = LocalDateTime.now()
      val balanceInfo = BalanceModel.Info(
        phone = phone,
        symbol = symbol,
        balance = 1.0,
        createTime = nowTime
      )

      implicit val ec = system.executionContext
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = 1,
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

      val closeProbe = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.Create(
          offset = Offset.close,
          volume = 2,
          latestPrice = 100
        )(closeProbe.ref)
      )
      closeProbe.expectMessage(PositionBase.CloseOk())

      val closeErrorProbe = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.Create(
          offset = Offset.close,
          volume = 1,
          latestPrice = 100
        )(closeErrorProbe.ref)
      )
      closeErrorProbe.expectMessage(
        PositionBase.CreateFail(
          PositionCreateFailStatus.createClosePositionNotExit
        )
      )

      val leverRateProbeYes = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.IsCanChangeLeverRate()(leverRateProbeYes.ref)
      )
      leverRateProbeYes.expectMessage(PositionBase.ChangeLeverRateYes())

    }

    "rate computer" in {
      val (socketClient, socketPort) = createSocket()
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

      positionBehavior.tell(
        PositionBase.ReplaceData(
          data = PositionBase.DataStore(
            position = Option(
              PositionBase.PositionInfo(
                direction = direction,
                volume = 1,
                available = 1,
                frozen = 0,
                openFee = 1 * 100.0 / 10000 * 0.004,
                closeFee = 0,
                costOpen = 10000.0,
                costHold = 10000.0,
                profitUnreal = 0,
                profitRate = 0,
                profit = 0,
                positionMargin = 100.0 * 1 / 10000 / 20,
                createTime = LocalDateTime.now()
              )
            ),
            contractAdjustfactors = Seq(
              ContractAdjustfactorModel.Info(
                symbol = CoinSymbol.BTC,
                leverRate = LeverRate.x20,
                ladder = 1,
                minSize = 0,
                maxSize = 99,
                adjustFactor = 0.1
              )
            ),
            config = PositionBase.Config(
              marketTradeId = socketPort
            ),
            price = 10000,
            phone = phone,
            symbol = symbol,
            contractType = contractType,
            direction = direction,
            leverRate = LeverRate.x20,
            entityId = positionId,
            contractSize = 100
          )
        )
      )

      val mockBalanceService = mock[BalanceRepository]
      val nowTime = LocalDateTime.now()
      val balanceInfo = BalanceModel.Info(
        phone = phone,
        symbol = symbol,
        balance = 0.001,
        createTime = nowTime
      )

      implicit val ec = system.executionContext
      when(mockBalanceService.balance(any, any)) thenAnswer (args =>
        Future(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = 1,
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

      val rateProbe = testKit.createTestProbe[BaseSerializer]()
      positionBehavior.tell(
        PositionBase.RateQuery()(rateProbe.ref)
      )
      rateProbe.expectMessageType[PositionBase.RateQueryOk]
    }

  }

}
