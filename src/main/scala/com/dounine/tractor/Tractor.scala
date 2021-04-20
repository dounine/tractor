package com.dounine.tractor

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.stream.SystemMaterializer
import com.dounine.tractor.router.routers.{BindRouters, CachingRouter, HealthRouter}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

object Tractor {
  private val logger = LoggerFactory.getLogger(Tractor.getClass)

  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.load().getConfig("app")
    val appName = config.getString("name")
    implicit val system = ActorSystem(Behaviors.empty, appName)
    implicit val materialize = SystemMaterializer(system).materializer
    implicit val executionContext = system.executionContext
    val routers = BindRouters(system)

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster: Cluster = Cluster.get(system)
    val managementRoutes: Route = ClusterHttpManagementRoutes(cluster)
    Http(system)
      .newServerAt(interface = config.getString("server.host"), port = config.getInt("server.port"))
      .bind(concat(routers, managementRoutes))
      .onComplete({
        case Failure(exception) => throw exception
        case Success(value) => logger.info(s"""${appName} server http://${value.localAddress.getHostName}:${value.localAddress.getPort} running""")
      })

  }

}
