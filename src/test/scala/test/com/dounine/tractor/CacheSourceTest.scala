package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.typed.{Cluster, Join}
import com.dounine.tractor.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.tractor.model.models.{BaseSerializer, UserModel}
import com.dounine.tractor.model.types.service.UserStatus
import com.dounine.tractor.service.UserService
import com.dounine.tractor.tools.akka.cache.CacheSource
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

class CacheSourceTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.extensions = ["com.dounine.tractor.tools.akka.cache.CacheSource"]
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          CacheSourceTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          CacheSourceTest
        ].getSimpleName}"
           |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with MockitoSugar
    with JsonParse {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))
  }

  "cache source test" should {

    "test cache normal" in {
      val cache = CacheSource(system)
      val value = cache
        .cache()
        .upcache(
          key = "test",
          ttl = 3.seconds,
          default = () => {
            Future.successful(1)
          }
        )
      value.futureValue shouldBe 1
      val value2 = cache
        .cache()
        .upcache(
          key = "test",
          ttl = 3.seconds,
          default = () => {
            Future.successful(2)
          }
        )
      value2.futureValue shouldBe 1
    }

    "test cache expire normal" in {
      val cache = CacheSource(system)
      val value = cache
        .cache()
        .upcache(
          key = "test1",
          ttl = 1.milliseconds,
          default = () => {
            Future.successful(1)
          }
        )
      value.futureValue shouldBe 1
      TimeUnit.MILLISECONDS.sleep(50)
      val value2 = cache
        .cache()
        .upcache(
          key = "test1",
          ttl = 10.milliseconds,
          default = () => {
            Future.successful(2)
          }
        )
      value2.futureValue shouldBe 2
    }

  }
}
