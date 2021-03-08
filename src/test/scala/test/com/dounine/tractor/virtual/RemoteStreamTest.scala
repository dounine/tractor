package test.com.dounine.tractor.virtual

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.stream.scaladsl.{BroadcastHub, Compression, Flow, Keep, Sink, Source, StreamRefs}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{BoundedSourceQueue, OverflowStrategy, SourceRef, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

object RemoteStream {

  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("RemoteStreamBehavior")

  sealed class Event extends BaseSerializer

  case class Sub()(val replyTo: ActorRef[Event]) extends Event

  case class SubOk(source: SourceRef[Event]) extends Event

  case class Push(info: Info) extends Event

  case class Info(
                   name: String
                 ) extends Event

  def apply(): Behavior[Event] = Behaviors.setup {
    context => {
      implicit val materializer = SystemMaterializer(context.system).materializer
      val client = Source.queue[Event](100)
        .preMaterialize()

      val brodcastHub = client._2.runWith(BroadcastHub.sink)

      Behaviors.receiveMessage {
        case e@Sub() => {
          val remoteSource = brodcastHub.runWith(StreamRefs.sourceRef())
          e.replyTo.tell(SubOk(remoteSource))
          Behaviors.same
        }
        case e@Push(info) => {
          client._1.offer(info)
          Behaviors.same
        }
      }
    }
  }
}

class RemoteStreamTest extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = 25526
       |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[RemoteStreamTest].getSimpleName}"
       |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[RemoteStreamTest].getSimpleName}"
       |""".stripMargin)
    .withFallback(
      ConfigFactory.parseResources("application-test.conf")
    )
    .resolve()
) with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val materializer = SystemMaterializer(system).materializer
  val sharding = ClusterSharding(system)
  val portGlobal = new AtomicInteger(8500)
  val orderIdGlobal = new AtomicInteger(1)
  val pingMessage = (time: Option[Long]) => Await.result(Source.single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""").map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)
  val dataMessage = (data: String) => Await.result(Source.single(data).map(ByteString(_)).via(Compression.gzip).runWith(Sink.head), Duration.Inf)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(file"/tmp/journal_${classOf[RemoteStreamTest].getSimpleName}", file"/tmp/snapshot_${classOf[RemoteStreamTest].getSimpleName}")
    try {
      files.filter(_.exists).foreach(_.delete())
    } catch {
      case e =>
    }

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(Entity(
      typeKey = RemoteStream.typeKey
    )(
      createBehavior = entityContext => RemoteStream()
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

  "remote stream behavior" should {
    "run" in {

      val remoteStream = sharding.entityRefFor(
        RemoteStream.typeKey,
        "hello"
      )

      val subProbe = testKit.createTestProbe[BaseSerializer]()
      remoteStream.tell(
        RemoteStream.Sub()(subProbe.ref)
      )
      val subProbe2 = testKit.createTestProbe[BaseSerializer]()
      remoteStream.tell(
        RemoteStream.Sub()(subProbe2.ref)
      )

      val subOk = subProbe.receiveMessage().asInstanceOf[RemoteStream.SubOk]
      val subOk2 = subProbe2.receiveMessage().asInstanceOf[RemoteStream.SubOk]

      val info = RemoteStream.Info(
        name = "abc"
      )
      remoteStream.tell(RemoteStream.Push(
        info
      ))

      subOk2.source.runWith(Sink.ignore)


      Source.future({
        remoteStream.ask[RemoteStream.Event](
          ref => RemoteStream.Sub()(ref)
        )(3.seconds)
      })
        .flatMapConcat {
          case RemoteStream.SubOk(source) => source
        }
        .throttle(1, 100.milliseconds)
        .buffer(1, OverflowStrategy.dropHead)
        .runWith(TestSink[RemoteStream.Event]()).request(1).expectNext(info)

    }
  }


}
