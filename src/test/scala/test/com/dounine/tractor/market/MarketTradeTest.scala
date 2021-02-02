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
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType}
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class MarketTradeTest extends ScalaTestWithActorTestKit(ManualTime.config) with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val globalGort = new AtomicInteger(8080)
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
          marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[MarketTradeBehavior.Event]().ref))
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
      marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[MarketTradeBehavior.Event]().ref))
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
      LoggingTestKit
        .info(classOf[MarketTradeBehavior.SocketConnected].getSimpleName)
        .expect {
          marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(Option(s"ws://127.0.0.1:${port}"))(testKit.createTestProbe[MarketTradeBehavior.Event]().ref))
        }

      val probeSource = testKit.createTestProbe[MarketTradeBehavior.Event]()

      LoggingTestKit
        .info(classOf[MarketTradeBehavior.Sub].getSimpleName)
        .expect {
          marketTradeBehavior.tell(MarketTradeBehavior.Sub(CoinSymbol.BTC, ContractType.quarter)(probeSource.ref))
        }

      val subResponse = probeSource.receiveMessage().asInstanceOf[MarketTradeBehavior.SubResponse]

      val messageData = """{"symbol":"BTC","contractType":"quarter","price":100}"""
      client.offer(BinaryMessage.Strict(
        binaryMessage(messageData)
      ))
      subResponse.source.runWith(TestSink()).request(1).expectNext(messageData.jsonTo[MarketTradeBehavior.TradeDetail])
    }

  }

}
