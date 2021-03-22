package com.dounine.tractor.behaviors.virtual.position

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.EntrustStatus.EntrustStatus
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.PositionCreateFailStatus.PositionCreateFailStatus

import java.time.LocalDateTime

object PositionBase {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("PositionBehavior")

  final case class PositionInfo(
      volume: Int,
      available: Int,
      frozen: Int,
      openFee: Double,
      closeFee: Double,
      costOpen: Double,
      costHold: Double,
      profitUnreal: Double,
      profitRate: Double,
      profit: Double,
      positionMargin: Double,
      createTime: LocalDateTime
  ) extends BaseSerializer

  final case class Config(
      marketTradeId: String = MarketTradeBehavior.typeKey.name,
      aggregationId: String = AggregationBehavior.typeKey.name
  ) extends BaseSerializer

  case class DataStore(
      position: Option[PositionInfo],
      price: Double,
      config: Config,
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      leverRate: LeverRate,
      entityId: String,
      contractSize: Int
  ) extends BaseSerializer

  sealed trait Command extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Stoped(data: DataStore) extends State

  final case class Idle(data: DataStore) extends State

  final case class Busy(data: DataStore) extends State

  final case class Run(
      marketTradeId: String,
      aggregationId: String,
      contractSize: Int
  ) extends Command

  final case class IsCanChangeLeverRate()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class ChangeLeverRateYes() extends Command

  final case class ChangeLeverRateNo() extends Command

  final case class UpdateLeverRate(value: LeverRate)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  final case class UpdateLeverRateOk() extends Command

  final case class UpdateLeverRateFail() extends Command

  final case class ReplaceData(data: DataStore) extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case object Recovery extends Command

  final case class Create(
      offset: Offset,
      volume: Int,
      latestPrice: Double
  )(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class CreateFail(status: PositionCreateFailStatus) extends Command

  final case class MarginQuery()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class MarginQueryOk(margin: Double) extends Command

  final case class MarginQueryFail(msg: String) extends Command

  final case class ProfitUnrealQuery()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class ProfitUnrealQueryOk(profitUnreal: Double) extends Command

  final case class ProfitUnrealQueryFail(msg: String) extends Command

  final case class OpenOk() extends Command

  final case class MergeOk() extends Command

  final case class CloseOk() extends Command

  final case class StreamComplete() extends Command

  final case class NewPosition(
      position: PositionInfo
  ) extends Command

  final case class MergePosition(
      position: PositionInfo
  ) extends Command

  final case class MergePositionOk(
      position: PositionInfo
  ) extends Command

  final case class MergePositionFail(
      msg: String,
      position: PositionInfo
  ) extends Command

  final case class RemovePosition() extends Command

  final case class RemovePositionOk() extends Command

  final case class RemovePositionFail(msg: String) extends Command

  def createEntityId(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      randomId: String = ""
  ): String = {
    s"${phone}-${symbol}-${contractType}-${direction}-${randomId}"
  }

}
