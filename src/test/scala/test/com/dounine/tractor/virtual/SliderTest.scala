package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  LoggingTestKit,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityTypeKey
}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{
  BroadcastHub,
  Compression,
  Flow,
  Keep,
  Sink,
  Source,
  StreamRefs
}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{
  BoundedSourceQueue,
  OverflowStrategy,
  SourceRef,
  SystemMaterializer
}
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.slider.SliderBehavior
import com.dounine.tractor.behaviors.updown.{UpDownBase, UpDownBehavior}
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
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction,
  Offset
}
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class SliderTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
       |akka.remote.artery.canonical.port = 25527
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          SliderTest
        ].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          SliderTest
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
    with JsonParse {
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)
  val portGlobal = new AtomicInteger(8800)
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
      file"/tmp/journal_${classOf[SliderTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[SliderTest].getSimpleName}"
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

  "slider behavior" should {
    "run" in {
      val (socketClient, socketPort) = createSocket()

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, socketPort)
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(connectProbe.ref)
      )

      val phone = "123456789"
      val symbol = CoinSymbol.BTC
      val contractType = ContractType.quarter
      val direction = Direction.buy
      val offset = Offset.open

      val sliderBehavior = system.systemActorOf(
        SliderBehavior(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          offset = offset
        ),
        "sliderBehavior"
      )

      LoggingTestKit
        .info(
          classOf[SliderBehavior.Run].getName
        )
        .expect(
          sliderBehavior.tell(
            SliderBehavior.Run(
              marketTradeId = socketPort,
              upDownId = Option.empty,
              maxValue = 100
            )
          )
        )

      val subProbe = testKit.createTestProbe[BaseSerializer]()
      sliderBehavior.tell(
        SliderBehavior.Sub()(subProbe.ref)
      )
      val source =
        subProbe.receiveMessage().asInstanceOf[SliderBehavior.SubOk].source

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
                price = 110,
                ts = System.currentTimeMillis()
              )
            )
          ),
          ts = System.currentTimeMillis()
        )

      socketClient.offer(
        BinaryMessage.Strict(
          dataMessage(
            triggerMessage.toJson
          )
        )
      )

      val source2 = testKit.createTestProbe[BaseSerializer]()
      sliderBehavior
        .tell(SliderBehavior.Sub()(source2.ref))
      source2
        .receiveMessage()
        .asInstanceOf[SliderBehavior.SubOk]
        .source
        .runWith(TestSink[BaseSerializer]())
        .request(1)
        .request(1)
        .expectNext(
          SliderBehavior.Push(
            initPrice = Option("110.0"),
            tradeValue = Option("50.0"),
            tradePrice = Option("110.0")
          )
        )

      socketClient.offer(
        BinaryMessage.Strict(
          dataMessage(
            triggerMessage
              .copy(
                tick = triggerMessage.tick.copy(
                  data = Seq(
                    triggerMessage.tick.data.head.copy(
                      price = 111
                    )
                  )
                )
              )
              .toJson
          )
        )
      )

      val source3 = testKit.createTestProbe[BaseSerializer]()
      sliderBehavior
        .tell(SliderBehavior.Sub()(source3.ref))
      source3
        .receiveMessage()
        .asInstanceOf[SliderBehavior.SubOk]
        .source
        .runWith(TestSink[BaseSerializer]())
        .request(1)
        .expectNext(
          SliderBehavior.Push(
            initPrice = Option("110.0"),
            tradeValue = Option("50.0"),
            tradePrice = Option("110.0")
          )
        )

      socketClient.offer(
        BinaryMessage.Strict(
          dataMessage(
            triggerMessage
              .copy(
                tick = triggerMessage.tick.copy(
                  data = Seq(
                    triggerMessage.tick.data.head.copy(
                      price = 150
                    )
                  )
                )
              )
              .toJson
          )
        )
      )

      TimeUnit.MILLISECONDS.sleep(50)

      val source4 = testKit.createTestProbe[BaseSerializer]()
      sliderBehavior
        .tell(SliderBehavior.Sub()(source4.ref))
      source4
        .receiveMessage()
        .asInstanceOf[SliderBehavior.SubOk]
        .source
        .runWith(TestSink[BaseSerializer]())
        .request(1)
        .expectNext(
          SliderBehavior.Push(
            initPrice = Option("150.0"),
            tradeValue = Option("50.0"),
            tradePrice = Option("150.0")
          )
        )

      sliderBehavior
        .tell(
          UpDownBase.PushDataInfo(
            UpDownBase.PushInfo(
              openTriggerPrice = Option(160)
            )
          )
        )

      val source5 = testKit.createTestProbe[BaseSerializer]()
      sliderBehavior
        .tell(SliderBehavior.Sub()(source5.ref))
      source5
        .receiveMessage()
        .asInstanceOf[SliderBehavior.SubOk]
        .source
        .runWith(TestSink[BaseSerializer]())
        .request(1)
        .expectNext(
          SliderBehavior.Push(
            initPrice = Option("150.0"),
            tradeValue = Option("50.0"),
            tradePrice = Option("150.0"),
            entrustPrice = Option("160.0"),
            entrustValue = Option("60.0")
          )
        )

    }
  }

}
