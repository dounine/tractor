package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ManualTime,
  ScalaTestWithActorTestKit
}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.concurrent.Promise
import scala.concurrent.duration._

class Stream2Test
    extends ScalaTestWithActorTestKit()
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with JsonParse {
  implicit val ec = system.executionContext

  "stream2 test" should {

    "reduce latter" in {
      val source1 = Source.single(1)
      val source2 = Source.single(10)

      source1
        .delay(50.milliseconds)
        .merge(source2)
        .reduce(_ - _)
        .runWith(TestSink())
        .request(1)
        .expectNext(9)

      source1
        .merge(source2.delay(50.milliseconds))
        .reduce(_ - _)
        .runWith(TestSink())
        .request(1)
        .expectNext(-9)
    }

    "flatMapMerge" in {
      Source
        .single(1)
        .flatMapMerge(3, item => Source(1 to 3))
        .runWith(TestSink())
        .request(3)
        .expectNext(1, 2, 3)

    }

  }
}
