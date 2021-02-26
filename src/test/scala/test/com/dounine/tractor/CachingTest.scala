package test.com.dounine.tractor

import akka.http.scaladsl.server.Directives.{complete, path}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import akka.http.scaladsl.server._
import Directives._
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.CacheDirectives.`no-cache`
import akka.http.scaladsl.model.headers.{Authorization, `Cache-Control`}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.directives.CachingDirectives.{cache, routeCache}
import com.dounine.tractor.router.routers.CachingRouter
import com.typesafe.config.ConfigFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

class CachingTest extends AnyWordSpec with Matchers with ScalatestRouteTest {

  import akka.actor.typed.scaladsl.adapter._

  val cachingRouter = new CachingRouter(system.toTyped).route

  "caching group" should {
    "caching is enable" in {
      Get("/cached") ~> cachingRouter ~> check {
        responseAs[String] shouldEqual """"1""""
      }
    }
    "access caching is enable" in {
      Get("/cached") ~> cachingRouter ~> check {
        responseAs[String] shouldEqual """"1""""
      }
    }
    "not caching is enable" in {
      Get("/cached") ~> `Cache-Control`(`no-cache`) ~> cachingRouter ~> check {
        responseAs[String] shouldEqual """"2""""
      }
    }
  }


}
