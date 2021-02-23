package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.internal.protobuf.ReliableDelivery.Cleanup
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.typed.PersistenceId
import akka.stream.{BoundedSourceQueue, KillSwitches, SystemMaterializer}
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction, LeverRate, Offset, OrderPriceType, TriggerType}
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class TriggerTest extends ScalaTestWithActorTestKit() with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val globalGort = new AtomicInteger(8100)


  "trigger behavior" should {
    "run" in {
      val materializer = SystemMaterializer(system).materializer
      val sharding = ClusterSharding(system)

      val time = System.currentTimeMillis()
      socketClient.offer(BinaryMessage.Strict(pingMessage(Option(time))))

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, MarketTradeBehavior.typeKey.name)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )
      val trigger = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter))
      LoggingTestKit.info(
        classOf[TriggerBase.RunSelfOk].getSimpleName
      )
        .expect(
          trigger.tell(TriggerBase.Run)
        )

    }

    "create and trigger1" in {
      val materializer = SystemMaterializer(system).materializer
      val sharding = ClusterSharding(system)

      val marketTrade = sharding.entityRefFor(MarketTradeBehavior.typeKey, MarketTradeBehavior.typeKey.name)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )
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
      val triggerBehavior = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter))
      triggerBehavior.tell(TriggerBase.Run)

      val createProbe = testKit.createTestProbe[BaseSerializer]()
      triggerBehavior.tell(TriggerBase.Create(
        orderId = "a",
        direction = Direction.buy,
        leverRate = LeverRate.x20,
        offset = Offset.open,
        orderPriceType = OrderPriceType.limit,
        triggerType = TriggerType.ge,
        orderPrice = 100,
        triggerPrice = 90,
        volume = 1
      )(createProbe.ref))

      createProbe.expectMessage(TriggerBase.CreateOk("a"))

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

      socketClient.offer(BinaryMessage.Strict(dataMessage(triggerMessage)))

    }

  }

  var socketClient: BoundedSourceQueue[Message] = _
  val socketPort = globalGort.getAndIncrement()
  val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val dataMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val journalDir = system.settings.config.getString("akka.persistence.journal.leveldb.dir")
    val snapshotDir = system.settings.config.getString("akka.persistence.snapshot-store.local.dir")
    val files = Seq(file"${journalDir}", file"${snapshotDir}")
    files.filter(_.exists).foreach(_.delete())

    val materializer = SystemMaterializer(system).materializer

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))


    val sink = Sink.ignore
    val ((client: BoundedSourceQueue[Message], close), source: Source[Message, NotUsed]) = Source.queue[Message](10)
      .viaMat(KillSwitches.single)(Keep.both)
      .preMaterialize()

    socketClient = client

    val receiveMessageFlow = Flow[Message]
      .collect {
        case TextMessage.Strict(text) => Future.successful(text)
        case TextMessage.Streamed(stream) => stream.runFold("")(_ ++ _)(materializer)
      }
      .mapAsync(1)(identity)
      .to(sink)

    val result = Flow.fromSinkAndSourceCoupledMat(
      sink = receiveMessageFlow,
      source = source
    )(Keep.right)

    val server = Await.result(Http(system)
      .newServerAt("0.0.0.0", socketPort)
      .bindFlow(handleWebSocketMessages(result))
      .andThen(_.get)(system.executionContext), Duration.Inf)

    val sharding = ClusterSharding(system)


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

}
