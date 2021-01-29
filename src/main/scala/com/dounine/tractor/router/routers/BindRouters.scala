package com.dounine.tractor.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object BindRouters extends SuportRouter {

  val requestTimeout = ConfigFactory.load().getDuration("akka.http.server.request-timeout").toMillis

  def apply(system: ActorSystem[_]): RequestContext => Future[RouteResult] = {
    Route.seal(
      /**
       * all request default timeout
       * child request can again use withRequestTimeout, Level child > parent
       */
      withRequestTimeout(requestTimeout.millis, (_: HttpRequest) => timeoutResponse)(
        concat(
          new HealthRouter(system).route,
          new CachingRouter(system).route,
          new SSERouter(system).route
        )
      )
    )
  }

}
