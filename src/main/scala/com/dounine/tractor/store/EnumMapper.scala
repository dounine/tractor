package com.dounine.tractor.store

import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.model.types.service.UserStatus
import com.dounine.tractor.model.types.service.UserStatus.UserStatus
import com.dounine.tractor.tools.json.JsonParse
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType
import slick.jdbc.MySQLProfile.api._

import java.sql.{Date, Timestamp}
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.duration.FiniteDuration

trait EnumMapper extends JsonParse {

  val localDateTime2timestamp
      : JdbcType[LocalDateTime] with BaseTypedType[LocalDateTime] =
    MappedColumnType.base[LocalDateTime, Timestamp](
      { instant =>
        if (instant == null) null else Timestamp.valueOf(instant)
      },
      { timestamp =>
        if (timestamp == null) null else timestamp.toLocalDateTime
      }
    )

  implicit val finiteDuration2String
      : JdbcType[FiniteDuration] with BaseTypedType[FiniteDuration] =
    MappedColumnType.base[FiniteDuration, String](
      e => e.toString,
      s => {
        val spl: Array[String] = s.split(" ")
        FiniteDuration(spl.head.toLong, spl.last)
      }
    )

  val localDate2timestamp: JdbcType[LocalDate] with BaseTypedType[LocalDate] =
    MappedColumnType.base[LocalDate, Date](
      { instant =>
        if (instant == null) null else Date.valueOf(instant)
      },
      { d =>
        if (d == null) null else d.toLocalDate
      }
    )

  implicit val userStatusMapper
      : JdbcType[UserStatus] with BaseTypedType[UserStatus] =
    MappedColumnType.base[UserStatus, String](
      e => e.toString,
      s => UserStatus.withName(s)
    )

  implicit val coinSymbolMapper
      : JdbcType[CoinSymbol] with BaseTypedType[CoinSymbol] =
    MappedColumnType.base[CoinSymbol, String](
      e => e.toString,
      s => CoinSymbol.withName(s)
    )

  implicit val contractTypeMapper
      : JdbcType[ContractType] with BaseTypedType[ContractType] =
    MappedColumnType.base[ContractType, String](
      e => e.toString,
      s => ContractType.withName(s)
    )

  implicit val directionMapper
      : JdbcType[Direction] with BaseTypedType[Direction] =
    MappedColumnType.base[Direction, String](
      e => e.toString,
      s => Direction.withName(s)
    )

  implicit val leverRateMapper
      : JdbcType[LeverRate] with BaseTypedType[LeverRate] =
    MappedColumnType.base[LeverRate, String](
      e => e.toString,
      s => LeverRate.withName(s)
    )

  implicit val offsetMapper: JdbcType[Offset] with BaseTypedType[Offset] =
    MappedColumnType.base[Offset, String](
      e => e.toString,
      s => Offset.withName(s)
    )

}
