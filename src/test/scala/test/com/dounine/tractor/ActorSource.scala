package test.com.dounine.tractor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, StreamRefs}
import akka.stream.{SourceRef, SystemMaterializer}
import com.dounine.tractor.model.models.BaseSerializer
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object ActorSource {

  private val logger = LoggerFactory.getLogger(ActorSource.getClass)

  trait Event extends BaseSerializer

  case class Sub()(val replyTo: ActorRef[BaseSerializer]) extends Event

  case class SubResponse(source: SourceRef[Int]) extends Event

  def apply(): Behavior[BaseSerializer] = Behaviors.setup {
    context =>
      implicit val materializer = SystemMaterializer(context.system).materializer
      val source = Source(1 to 3)
        .throttle(1, 1.seconds)
      Behaviors.receiveMessage {
        case e@Sub() => {
          logger.info("receive sub message")
          val broadcastHub = source.toMat(BroadcastHub.sink)(Keep.right).run()
          val sourceRef = broadcastHub.runWith(StreamRefs.sourceRef())
          e.replyTo.tell(SubResponse(sourceRef))
          Behaviors.same
        }
      }
  }

}
