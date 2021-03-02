package com.dounine.tractor.tools.json

import akka.actor.typed.ActorRef
import com.dounine.tractor.model.models.TriggerModel
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction, EntrustCancelFailStatus, EntrustStatus, LeverRate, Offset, OrderPriceType, PositionCreateFailStatus, TriggerCancelFailStatus, TriggerStatus, TriggerType}
import com.dounine.tractor.model.types.router.ResponseCode
import org.json4s.JsonAST.{JField, JLong, JObject, JString}
import org.json4s.ext.EnumNameSerializer
import org.json4s.jackson.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Formats, jackson}

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.duration.FiniteDuration

object JsonSuport {

  object LocalDateTimeSerializer
    extends CustomSerializer[LocalDateTime](_ =>
      ( {
        case JString(dateTime) =>
          LocalDateTime.parse(dateTime, dateTimeFormatter)
      }, {
        case dateTime: LocalDateTime =>
          JString(dateTime.toString)
      }
      )
    )

  object LocalDateSerializer
    extends CustomSerializer[LocalDate](_ =>
      ( {
        case JString(date) =>
          LocalDate.parse(date)
      }, {
        case date: LocalDate =>
          JString(date.toString)
      }
      )
    )

  /**
   * only using for log serialaizer
   */
  object ActorRefSerializer
    extends CustomSerializer[ActorRef[_]](_ =>
      ( {
        case JString(_) => null
      }, {
        case actor: ActorRef[_] =>
          JString(actor.toString)
      }
      )
    )

  object FiniteDurationSerializer
    extends CustomSerializer[FiniteDuration](_ =>
      ( {
        case JObject(
        JField("unit", JString(unit)) :: JField(
        "length",
        JLong(length)
        ) :: Nil
        ) =>
          FiniteDuration(length.toLong, unit)
      }, {
        case time: FiniteDuration =>
          JObject(
            "unit" -> JString(time._2.name()),
            "length" -> JLong(time._1.toLong)
          )
      }
      )
    )

  val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T'][HH:mm[:ss[.SSS]]][X]")
  val serialization: Serialization.type = jackson.Serialization
  val formats: Formats = DefaultFormats +
    LocalDateTimeSerializer +
    LocalDateSerializer +
    FiniteDurationSerializer ++ Seq(
    CoinSymbol,
    ContractType,
    ResponseCode,
    Direction,
    LeverRate,
    Offset,
    OrderPriceType,
    TriggerStatus,
    TriggerType,
    TriggerCancelFailStatus,
    EntrustStatus,
    EntrustCancelFailStatus,
    PositionCreateFailStatus
  ).map(new EnumNameSerializer(_))
}
