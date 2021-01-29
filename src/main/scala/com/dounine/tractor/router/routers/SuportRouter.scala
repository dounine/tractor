package com.dounine.tractor.router.routers

import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.StandardRoute
import com.dounine.tractor.model.models.RouterModel
import com.dounine.tractor.tools.json.JsonParse

trait SuportRouter extends JsonParse {

  def ok(data: Any): StandardRoute = {
    complete(RouterModel.Data(Option(data)))
  }

  val ok: StandardRoute = complete(RouterModel.Ok())

  def fail(msg: String): StandardRoute = {
    complete(RouterModel.Fail(Option(msg)))
  }

  val fail: StandardRoute = complete(RouterModel.Fail())


}
