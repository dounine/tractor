package test.com.dounine.tractor

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.management.scaladsl.AkkaManagement
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.typesafe.config.ConfigFactory

object MarkTradeMainTest {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load().getConfig("app")
    val system = ActorSystem(Behaviors.empty,"tractor")
    val marketTradeBehavior = system.systemActorOf(MarketTradeBehavior(),"market")
    marketTradeBehavior.tell(MarketTradeBehavior.SocketConnect(None)(null))
  }

}
