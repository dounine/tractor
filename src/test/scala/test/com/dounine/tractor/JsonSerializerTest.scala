package test.com.dounine.tractor

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.service.UserStatus
import com.dounine.tractor.model.types.service.UserStatus.UserStatus
import com.dounine.tractor.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class JsonSerializerTest
    extends Matchers
    with AnyWordSpecLike
    with LogCapturing
    with JsonParse {

  case class Data[T](
      data: Option[T]
  ) extends BaseSerializer
  case class ChildData[T](
      name: String,
      age: Int,
      status: UserStatus,
      data: T
  ) extends BaseSerializer

  case class CChildData(
      name: String
  ) extends BaseSerializer
  "json serializer test" should {

    "normal serializer" in {
      val pojoData = new Data[ChildData[CChildData]](
        data = Option(
          new ChildData(
            name = "lake",
            age = 18,
            status = UserStatus.normal,
            data = CChildData("hello")
          )
        )
      )

      info(pojoData.toJson)
    }

    "pojo to string deserialization" in {
      val pojoData = new Data[ChildData[CChildData]](
        data = Option(
          new ChildData(
            name = "lake",
            age = 18,
            status = UserStatus.normal,
            data = CChildData("hello")
          )
        )
      )

      val json = pojoData.toJson

      val pojoD = json.jsonTo[Data[Map[String, Any]]]
      info(pojoD.toString)
      val cc = pojoD.data.toJson.jsonTo[ChildData[CChildData]]
      info(cc.toString)
    }

  }
}
