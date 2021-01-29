package com.dounine.tractor.router.routers

import akka.actor.typed.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

class HealthRouter(system: ActorSystem[_]) extends SuportRouter {
  val cluster: Cluster = Cluster.get(system)


  def apply(): Route = get {
    path("ready") {
      ok
    } ~ path("alive") {
      if (
        cluster.selfMember.status == MemberStatus.Up || cluster.selfMember.status == MemberStatus.WeaklyUp
      ) {
        ok
      } else {
        complete(StatusCodes.NotFound)
      }
    }
  }
}
