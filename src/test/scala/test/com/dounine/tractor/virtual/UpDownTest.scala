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
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.updown.{UpDownBase, UpDownBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.{EntrustBase, EntrustBehavior}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{PositionBase, PositionBehavior}
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel, NotifyModel}
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class UpDownTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25525
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[UpDownTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[UpDownTest].getSimpleName}"
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
    val files = Seq(file"/tmp/journal_${classOf[UpDownTest].getSimpleName}", file"/tmp/snapshot_${classOf[UpDownTest].getSimpleName}")
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
      typeKey = PositionBase.typeKey
    )(
      createBehavior = entityContext => PositionBehavior(
        PersistenceId.of(
          PositionBase.typeKey.name,
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


    sharding.init(Entity(
      typeKey = EntrustNotifyBehavior.typeKey
    )(
      createBehavior = entityContext => EntrustNotifyBehavior()
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
      typeKey = UpDownBase.typeKey
    )(
      createBehavior = entityContext => UpDownBehavior(
        PersistenceId.of(
          UpDownBase.typeKey.name,
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

  "updown behavior" should {
    "run" in {
      val (socketClient, socketPort) = createSocket()
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val phone = "123456789"
      val symbol = CoinSymbol.BTC
      val contractType = ContractType.quarter
      val direction = Direction.buy

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val positionBehavior = sharding.entityRefFor(PositionBase.typeKey, positionId)
      positionBehavior.tell(PositionBase.Run(
        marketTradeId = socketPort
      ))

      val entrustId = EntrustBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      entrustBehavior.tell(EntrustBase.Run(
        marketTradeId = socketPort,
        positionId = positionId,
        entrustNotifyId = socketPort
      ))

      val triggerId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, triggerId)
      triggerBehavior.tell(TriggerBase.Run(
        marketTradeId = socketPort,
        entrustId = entrustId
      ))

      val updownId = UpDownBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)


      LoggingTestKit.info(
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
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val phone = "123456789"
      val symbol = CoinSymbol.BTC
      val contractType = ContractType.quarter
      val direction = Direction.buy

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val positionBehavior = sharding.entityRefFor(PositionBase.typeKey, positionId)
      positionBehavior.tell(PositionBase.Run(
        marketTradeId = socketPort
      ))

      val entrustId = EntrustBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      entrustBehavior.tell(EntrustBase.Run(
        marketTradeId = socketPort,
        positionId = positionId,
        entrustNotifyId = socketPort
      ))

      val triggerId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, triggerId)
      triggerBehavior.tell(TriggerBase.Run(
        marketTradeId = socketPort,
        entrustId = entrustId
      ))

      val updownId = UpDownBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

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

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )

      LoggingTestKit.info(
        classOf[TriggerBase.Create].getName
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
      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val phone = "123456789"
      val symbol = CoinSymbol.BTC
      val contractType = ContractType.quarter
      val direction = Direction.buy

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      val positionId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val positionBehavior = sharding.entityRefFor(PositionBase.typeKey, positionId)
      positionBehavior.tell(PositionBase.Run(
        marketTradeId = socketPort
      ))

      val entrustId = EntrustBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val entrustBehavior = sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      entrustBehavior.tell(EntrustBase.Run(
        marketTradeId = socketPort,
        positionId = positionId,
        entrustNotifyId = socketPort
      ))

      val triggerId = TriggerBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, triggerId)
      triggerBehavior.tell(TriggerBase.Run(
        marketTradeId = socketPort,
        entrustId = entrustId
      ))

      val updownId = UpDownBase.createEntityId(phone, symbol, contractType, direction, socketPort)
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

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

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = socketPort,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )


      val updateProbe = testKit.createTestProbe[BaseSerializer]()
      updownBehavior.tell(UpDownBase.Update(
        UpDownUpdateType.openScheduling,
        1.seconds,
        updateProbe.ref
      ))
      updateProbe.expectMessage(UpDownBase.UpdateOk())

      socketClient.offer(
        BinaryMessage.Strict(
          dataMessage(triggerMessage)
        )
      )

      LoggingTestKit.error(
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


  }


}
