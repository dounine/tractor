package test.com.dounine.tractor.virtual

import akka.actor.testkit.typed.scaladsl.{LogCapturing, LoggingTestKit, ManualTime, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.typed.PersistenceId
import akka.stream.SystemMaterializer
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.{TriggerBase, TriggerBehavior}
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType}
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger

class TriggerTest extends ScalaTestWithActorTestKit() with Matchers with AnyWordSpecLike with LogCapturing with JsonParse {
  val globalGort = new AtomicInteger(8080)

  "trigger behavior" should {
    "create" in {
      implicit val materializer = SystemMaterializer(system).materializer
      val port = globalGort.getAndIncrement()

      val cluster = Cluster.get(system)
      cluster.manager.tell(Join.create(cluster.selfMember.address))

      val sharding = ClusterSharding(system)

      sharding.init(Entity(
        typeKey = MarketTradeBehavior.typeKey
      )(
        createBehavior = entityContext => MarketTradeBehavior()
      ))
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
        TriggerBase.Run.getClass.getSimpleName
      )
        .expect(
          trigger.tell(TriggerBase.Run)
        )

    }
  }

}
