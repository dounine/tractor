package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LoggingTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Compression, Flow, Keep, RestartSource, Sink, Source}
import akka.stream.typed.scaladsl.{ActorFlow, ActorSink}
import akka.stream.{BoundedSourceQueue, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.{EntrustBase, EntrustBehavior}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{PositionBase, PositionBehavior}
import com.dounine.tractor.behaviors.virtual.trigger.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.models.{BaseSerializer, MarketTradeModel}
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object MessageBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(MessageBehavior.getClass)
  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("MessageBehavior")


  sealed class Event extends BaseSerializer

  case class Cancel(
                     delay: FiniteDuration,
                     id: String
                   )(
                     val replyTo: ActorRef[Event]
                   ) extends Event

  case class CancelOk() extends Event

  case class CancelFail() extends Event

  case class StreamComplete() extends Event

  case class StreamFailed(exception: Throwable) extends Event


  def apply(): Behavior[Event] = Behaviors.setup {
    context => {
      Behaviors.receiveMessage {
        case e@Cancel(delay: FiniteDuration, id) => {
          logger.info(e.logJson)
          context.system.scheduler.scheduleOnce(delay, () => {
            e.replyTo.tell(CancelOk())
          })(context.executionContext)
          Behaviors.same
        }
        case e@StreamComplete() => {
          logger.info(e.logJson)
          Behaviors.same
        }
        case e@StreamFailed(exception) => {
          logger.info(e.logJson)
          Behaviors.same
        }
      }
    }
  }
}

class ActorStreamTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25524
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[ActorStreamTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[ActorStreamTest].getSimpleName}"
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
    val files = Seq(file"/tmp/journal_${classOf[ActorStreamTest].getSimpleName}", file"/tmp/snapshot_${classOf[ActorStreamTest].getSimpleName}")
    try {
      files.filter(_.exists).foreach(_.delete())
    } catch {
      case e =>
    }

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(Entity(
      typeKey = MessageBehavior.typeKey
    )(
      createBehavior = entityContext => MessageBehavior()
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

  "updown test" should {
    "cancel ok" in {
      val messageTrade = sharding.entityRefFor(MessageBehavior.typeKey, "abc")
      val sinkProbe = testKit.createTestProbe[MessageBehavior.Event]()
      Source.future(
        messageTrade.ask[MessageBehavior.Event](ref =>
          MessageBehavior.Cancel(500.milliseconds, "abc")(ref)
        )(2000.milliseconds)
      )
        .runWith(ActorSink.actorRef(
          ref = sinkProbe.ref,
          onCompleteMessage = MessageBehavior.StreamComplete(),
          onFailureMessage = e => MessageBehavior.StreamFailed(e)
        ))
      sinkProbe.expectMessage(MessageBehavior.CancelOk())
    }

    "cancel timeout complete" in {
      val messageTrade = sharding.entityRefFor(MessageBehavior.typeKey, "abc")
      val sinkProbe = testKit.createTestProbe[MessageBehavior.Event]()
      Source.future(
        messageTrade.ask[MessageBehavior.Event](ref =>
          MessageBehavior.Cancel(1.milliseconds, "abc")(ref)
        )(100.milliseconds)
      )
        .runWith(ActorSink.actorRef(
          ref = sinkProbe.ref,
          onCompleteMessage = MessageBehavior.StreamComplete(),
          onFailureMessage = e => MessageBehavior.StreamFailed(e)
        ))

      sinkProbe.expectMessage(MessageBehavior.CancelOk())
      sinkProbe.expectMessage(MessageBehavior.StreamComplete())
    }

    "cancel timeout fail" in {
      val messageTrade = sharding.entityRefFor(MessageBehavior.typeKey, "abc")
      val sinkProbe = testKit.createTestProbe[MessageBehavior.Event]()
      Source.future(
        messageTrade.ask[MessageBehavior.Event](MessageBehavior.Cancel(500.milliseconds, "abc")(_))(100.milliseconds)
      )
        .runWith(ActorSink.actorRef(
          ref = sinkProbe.ref,
          onCompleteMessage = MessageBehavior.StreamComplete(),
          onFailureMessage = e => MessageBehavior.StreamFailed(e)
        ))

      sinkProbe.expectMessageType[MessageBehavior.StreamFailed]

    }


  }


}
