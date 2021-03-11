package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  LoggingTestKit,
  ManualTime,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{KillSwitches, OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.behaviors.updown.UpDownBase
import com.dounine.tractor.behaviors.updown.UpDownBase.Command
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.UpDownUpdateType
import com.dounine.tractor.model.types.currency.UpDownUpdateType.UpDownUpdateType
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class StreamTest
    extends ScalaTestWithActorTestKit(ManualTime.config)
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with JsonParse {
  implicit val ec = system.executionContext
  val manualTime: ManualTime = ManualTime()

  "stream test" should {
    "manualTime test" in {
      val source = Source(1 to 3)
        .delay(1.seconds)
      val probe = testKit.createTestProbe[Int]()
      source.runForeach(probe.tell)
      manualTime.timePasses(3.seconds)
      probe.expectMessage(1)
    }
    "source for broadcast" in {
      val source = Source.maybe[Int].concat(Source(1 to 3))
      val (close, broadcastHub) = source
        .toMat(BroadcastHub.sink[Int](bufferSize = 8))(Keep.both)
        .run()

      val f1 = broadcastHub.runWith(Sink.seq)
      val f2 = broadcastHub.runWith(Sink.seq)

      TimeUnit.MILLISECONDS.sleep(50)
      close.success(None)

      f1.futureValue should ===((1 to 3).toSeq)
      f2.futureValue should ===((1 to 3).toSeq)
    }
    "broadcast sink" in {
      val source = Source(1 to 3)
        .throttle(1, 1.seconds)
      val broadcastHub = source.toMat(BroadcastHub.sink)(Keep.right).run()

      broadcastHub.runWith(TestSink[Int]()).request(1).expectNext(1)
      broadcastHub.runWith(TestSink[Int]()).request(1).expectNext(1)
    }
  }
}
