package test.com.dounine.tractor.market

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.{BoundedSourceQueue, KillSwitches, QueueCompletionResult, QueueOfferResult, SystemMaterializer}
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction}
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class MarketTradeTest extends ScalaTestWithActorTestKit(ManualTime.config) with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val globalGort = new AtomicInteger(8000)
  "market trade" should {
    "send ping message and response" in {
      implicit val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()
      val probe = testKit.createTestProbe[String]()
      val sink = Sink.foreach(probe.ref.tell)
      val ((client: BoundedSourceQueue[Message], close), source: Source[Message, NotUsed]) = Source.queue[Message](10)
        .viaMat(KillSwitches.single)(Keep.both)
        .preMaterialize()

      val receiveMessageFlow = Flow[Message]
        .collect {
          case TextMessage.Strict(text) => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold("")(_ ++ _)(materializer)
        }
        .mapAsync(1)(identity)
        .to(sink)

      val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

      val result = Flow.fromSinkAndSourceCoupledMat(
        sink = receiveMessageFlow,
        source = source
      )(Keep.right)

      val server = Await.result(Http(system)
        .newServerAt("0.0.0.0", port)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext), Duration.Inf)

      val time = System.currentTimeMillis()
      client.offer(BinaryMessage.Strict(pingMessage(Option(time))))
      val marketTradeBehavior = testKit.spawn(MarketTradeBehavior())
      LoggingTestKit.info(classOf[MarketTradeBehavior.SocketConnect].getSimpleName)
        .withMessageContains(classOf[MarketTradeBehavior.SocketConnect].getSimpleName)
        .expect {
          marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[BaseSerializer]().ref))
        }
      probe.expectMessage(s"""{"pong":${time}}""")
    }

    "socket close normal single for server" in {
      implicit val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()
      val probe = testKit.createTestProbe[String]()
      val sink = Sink.foreach[String](probe.ref.tell)
      val ((client: BoundedSourceQueue[Message], close), source: Source[Message, NotUsed]) = Source.queue[Message](10)
        .viaMat(KillSwitches.single)(Keep.both)
        .preMaterialize()

      val receiveMessageFlow = Flow[Message]
        .collect {
          case TextMessage.Strict(text) => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold("")(_ ++ _)(materializer)
        }
        .mapAsync(1)(identity)
        .to(sink)

      val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
      val result = Flow.fromSinkAndSourceCoupledMat(
        sink = receiveMessageFlow,
        source = source
      )(Keep.right)

      val server = Await.result(Http(system)
        .newServerAt("0.0.0.0", port)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext), Duration.Inf)

      assert(client.offer(BinaryMessage.Strict(pingMessage(None))) == QueueOfferResult.Enqueued)
      val marketTradeBehavior = testKit.spawn(MarketTradeBehavior())
      marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[BaseSerializer]().ref))
      LoggingTestKit
        .info(classOf[MarketTradeBehavior.SocketClosed].getSimpleName)
        .expect {
          close.shutdown()
          client.complete()
        }
    }

    "sub and run source" in {
      implicit val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()
      val probe = testKit.createTestProbe[String]()
      val sink = Sink.foreach[String](probe.ref.tell)
      val ((client: BoundedSourceQueue[Message], close), source: Source[Message, NotUsed]) = Source.queue[Message](10)
        .viaMat(KillSwitches.single)(Keep.both)
        .preMaterialize()

      val receiveMessageFlow = Flow[Message]
        .collect {
          case TextMessage.Strict(text) => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold("")(_ ++ _)(materializer)
        }
        .mapAsync(1)(identity)
        .to(sink)

      val binaryMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

      val result = Flow.fromSinkAndSourceCoupledMat(
        sink = receiveMessageFlow,
        source = source
      )(Keep.right)

      val server = Await.result(Http(system)
        .newServerAt("0.0.0.0", port)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext), Duration.Inf)

      val marketTradeBehavior = testKit.spawn(MarketTradeBehavior())
      marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[BaseSerializer]().ref))

      val probeSource = testKit.createTestProbe[BaseSerializer]()

      marketTradeBehavior.tell(MarketTradeBehavior.Sub(CoinSymbol.BTC, ContractType.quarter)(probeSource.ref))

      val subResponse = probeSource.receiveMessage().asInstanceOf[MarketTradeBehavior.SubResponse]

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
      )
      val tradeDetail = MarketTradeBehavior.TradeDetail(
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = triggerMessage.tick.data.head.direction,
        price = triggerMessage.tick.data.head.price,
        amount = triggerMessage.tick.data.head.amount,
        time = triggerMessage.tick.data.head.ts
      )

      client.offer(BinaryMessage.Strict(
        binaryMessage(triggerMessage.toJson)
      ))
      subResponse.source.runWith(TestSink()).request(1).expectNext(tradeDetail)
    }

    "multi sub and run source" in {
      implicit val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()
      val probe = testKit.createTestProbe[String]()
      val sink = Sink.foreach[String](probe.ref.tell)
      val ((client: BoundedSourceQueue[Message], close), source: Source[Message, NotUsed]) = Source.queue[Message](10)
        .viaMat(KillSwitches.single)(Keep.both)
        .preMaterialize()

      val receiveMessageFlow = Flow[Message]
        .collect {
          case TextMessage.Strict(text) => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold("")(_ ++ _)(materializer)
        }
        .mapAsync(1)(identity)
        .to(sink)

      val binaryMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

      val result = Flow.fromSinkAndSourceCoupledMat(
        sink = receiveMessageFlow,
        source = source
      )(Keep.right)

      val server = Await.result(Http(system)
        .newServerAt("0.0.0.0", port)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext), Duration.Inf)

      val marketTradeBehavior = testKit.spawn(MarketTradeBehavior())
      marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[BaseSerializer]().ref))

      val probeSource = testKit.createTestProbe[BaseSerializer]()
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
      )
      val tradeDetail = MarketTradeBehavior.TradeDetail(
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = triggerMessage.tick.data.head.direction,
        price = triggerMessage.tick.data.head.price,
        amount = triggerMessage.tick.data.head.amount,
        time = triggerMessage.tick.data.head.ts
      )

      client.offer(BinaryMessage.Strict(
        binaryMessage(triggerMessage.toJson)
      ))
      marketTradeBehavior.tell(MarketTradeBehavior.Sub(CoinSymbol.BTC, ContractType.quarter)(probeSource.ref))

      val probeSource2 = testKit.createTestProbe[BaseSerializer]()
      marketTradeBehavior.tell(MarketTradeBehavior.Sub(CoinSymbol.BTC, ContractType.quarter)(probeSource2.ref))

      val subResponse = probeSource.receiveMessage().asInstanceOf[MarketTradeBehavior.SubResponse]
      val subResponse2 = probeSource2.receiveMessage().asInstanceOf[MarketTradeBehavior.SubResponse]
      subResponse.source.runWith(TestSink()).request(1).expectNext(tradeDetail)
      subResponse2.source.runWith(TestSink()).request(1).expectNext(tradeDetail)

    }


  }

}
