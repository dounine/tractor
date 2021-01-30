package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class StreamTest extends ScalaTestWithActorTestKit(ManualTime.config) with Matchers with AnyWordSpecLike with LogCapturing {
  implicit val materializer = SystemMaterializer(testKit.system).materializer
  implicit val ec = testKit.system.executionContext
  val manualTime: ManualTime = ManualTime()

  "stream test" must {
    "multi use source" in {
      val source = Source(1 to 3)
        .throttle(1, 1.seconds)

      source.runWith(TestSink[Int]()).request(1).expectNext(1)
      manualTime.timePasses(1100.millis)
      source.runWith(TestSink[Int]()).request(1).expectNext(1)
    }
    "source for broadcast fast" in {
      val source = Source(1 to 4)
      val broadcastHub = source.runWith(BroadcastHub.sink[Int](bufferSize = 2))

      val probe = testKit.createTestProbe[Seq[Int]]()
      broadcastHub.runWith(Sink.seq)
        .onComplete({
          case Failure(exception) => throw exception
          case Success(value) => {
            probe.tell(value)
          }
        })


      val probe2 = testKit.createTestProbe[Seq[Int]]()
      broadcastHub.runWith(Sink.seq)
        .onComplete({
          case Failure(exception)  => throw exception
          case Success(value) => {
            probe2.tell(value)
          }
        })
      probe.expectMessage(Seq(1, 2, 3, 4))
      probe2.expectMessage(Seq(1, 2, 3, 4))

    }
    "source for broadcast" ignore {
      val source = Source(1 to 3)
        .throttle(1,100.millis)
        .watchTermination()((_, done) => {
          done.onComplete {
            case Failure(exception) => println("------", exception.getMessage)
            case Success(value) => {
              info(value.toString)
            }
          }
        })
      val broadcastHub = source
        .runWith(BroadcastHub.sink[Int](bufferSize = 1))
      //        .buffer(10, OverflowStrategy.dropHead)

      broadcastHub.take(1).runWith(TestSink[Int]()).request(1).expectNext(100.millis, 1)
      //      manualTime.timePasses(100.seconds)
      broadcastHub.take(1).runWith(TestSink[Int]()).request(1).expectNext(100.millis, 1)
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
