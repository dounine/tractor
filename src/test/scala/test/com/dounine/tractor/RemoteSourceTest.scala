package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.stream.SystemMaterializer
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.model.models.BaseSerializer
import org.scalatest.Ignore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class RemoteSourceTest extends ScalaTestWithActorTestKit(ManualTime.config) with Matchers with AnyWordSpecLike with LogCapturing {
  implicit val materializer = SystemMaterializer(testKit.system).materializer
  val manualTime: ManualTime = ManualTime()

  "remote source" must {

    "receive sub response" in {
      val sourceActor = testKit.spawn(ActorSource())
      val probe = testKit.createTestProbe[BaseSerializer]()
      LoggingTestKit.info("receive sub message").expect {
        sourceActor ! ActorSource.Sub()(probe.ref)
      }
      manualTime.timePasses(500.millis)
      val subResponse = probe.receiveMessage(1.millis).asInstanceOf[ActorSource.SubResponse]
      val stringProbe = testKit.createTestProbe[Int]()
      subResponse
        .source
        .take(1)
        .runForeach(stringProbe.tell)
      manualTime.timePasses(1.seconds)
      stringProbe.expectMessage(1)
    }

    "broadcast receive message" in {
      val sourceActor = testKit.spawn(ActorSource())
      val probe1 = testKit.createTestProbe[BaseSerializer]()
      LoggingTestKit.info("receive sub message")
        .expect {
          sourceActor ! ActorSource.Sub()(probe1.ref)
        }
      val subResponse1 = probe1.receiveMessage(500.millis).asInstanceOf[ActorSource.SubResponse]
      val probe2 = testKit.createTestProbe[BaseSerializer]()
      LoggingTestKit.info("receive sub message")
        .expect {
          sourceActor ! ActorSource.Sub()(probe2.ref)
        }
      val subResponse2 = probe2.receiveMessage(500.millis).asInstanceOf[ActorSource.SubResponse]

      subResponse1.source.take(1).runForeach(println)
      manualTime.timePasses(3.seconds)
      subResponse2.source.take(1).runForeach(println)

//      subResponse1.source.runWith(TestSink[Int]()).request(1).expectNext(1)
//      subResponse2.source.runWith(TestSink[Int]()).request(1).expectNext(1)

    }
  }

}
