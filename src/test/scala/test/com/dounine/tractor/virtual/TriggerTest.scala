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
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType}
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
    "create" in {
      val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()

      val cluster = Cluster.get(system)
      cluster.manager.tell(Join.create(cluster.selfMember.address))

      val sharding = ClusterSharding(system)

      val sink = Sink.ignore
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

      val marketTrade = sharding.init(Entity(
        typeKey = MarketTradeBehavior.typeKey
      )(
        createBehavior = entityContext => MarketTradeBehavior()
      ))
      val connectProbe = testKit.createTestProbe[BaseSerializer]()
      marketTrade.tell(
        ShardingEnvelope(MarketTradeBehavior.typeKey.name, MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${port}")
        )(connectProbe.ref))
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
      val trigger = sharding.entityRefFor(TriggerBase.typeKey, TriggerBase.createEntityId("13535032936", CoinSymbol.BTC, ContractType.quarter))

      LoggingTestKit.info(
        classOf[TriggerBase.RunSelfOk].getSimpleName
      )
        .expect(
          trigger.tell(TriggerBase.Run)
        )

    }
  }

  override protected def beforeAll(): Unit = {
    import better.files._
    val files = Seq(file"/tmp/journal", file"/tmp/snapshot")
    files.filter(_.exists).foreach(_.delete())
  }

}
