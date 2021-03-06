package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ManualTime, ScalaTestWithActorTestKit}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.concurrent.Promise
import scala.concurrent.duration._

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

    "reduce for stream1" in {
      val balance = Source.single(10)
      val positionMargin = Source.single(1)
      val entrustMargin = Source.single(3)

      balance
        .concat(
          positionMargin
            .concat(entrustMargin)
            .fold(0)((sum, next) => sum + next)
        )
        .reduce((balance, margin) => balance - margin)
        .runWith(TestSink())
        .request(1)
        .expectNext(6)
    }

    "reduce for stream2" in {
      val balance = Source.single(10)
      val positionMargin = Source.single(1)
      val entrustMargin = Source.single(3)

      balance
        .concat(positionMargin)
        .reduce((balance, margin) => balance - margin)
        .concat(entrustMargin)
        .reduce((balance, margin) => balance - margin)
        .runWith(TestSink())
        .request(1)
        .expectNext(6)
    }

    "brodcast test2" in {
      val promise = Promise[Int]()
      system.scheduler.scheduleOnce(
        50.milliseconds,
        () => {
          promise.success(1)
        }
      )
      manualTime.timePasses(60.milliseconds)
      val source = Source.future(promise.future)

      source.runWith(TestSink()).request(1).expectNext(1)
      source.runWith(TestSink()).request(1).expectNext(1)
      source.runWith(TestSink()).request(1).expectNext(1)
    }

    "broadcast test3" in {
      val positionList = Source(1 to 3)
        .delay(100.milliseconds)

      val positionListFuture = positionList.runWith(Sink.seq)

      val balanceSource = Source.empty[Int]

      manualTime.timePasses(150.milliseconds)
      val sum = Source
        .future(
          positionListFuture
        )
        .mapConcat(identity)
        .merge(balanceSource)
        .fold(0)(_ + _)

      sum.runWith(TestSink()).request(1).expectNext(6)
    }

  }
}
