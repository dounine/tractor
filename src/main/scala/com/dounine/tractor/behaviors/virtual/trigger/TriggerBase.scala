package com.dounine.tractor.behaviors.virtual.trigger

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.TriggerCancelFailStatus.TriggerCancelFailStatus
import com.dounine.tractor.model.types.currency.TriggerCreateFailStatus.TriggerCreateFailStatus
import com.dounine.tractor.model.types.currency.TriggerStatus.TriggerStatus
import com.dounine.tractor.model.types.currency.TriggerType.TriggerType
import com.dounine.tractor.tools.json.ActorSerializerSuport

import java.time.LocalDateTime

object TriggerBase extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("TriggerBehavior")

  case class TriggerItem(
      offset: Offset,
      orderPriceType: OrderPriceType,
      triggerType: TriggerType,
      orderPrice: Double,
      triggerPrice: Double,
      volume: Int,
      time: LocalDateTime
  ) extends BaseSerializer

  case class Config(
      marketTradeId: String = MarketTradeBehavior.typeKey.name,
      entrustId: String = EntrustBase.typeKey.name,
      aggregationId: String = AggregationBehavior.typeKey.name
  ) extends BaseSerializer

  case class TriggerInfo(
      trigger: TriggerItem,
      status: TriggerStatus
  ) extends BaseSerializer

  case class DataStore(
      price: Option[Double],
      triggers: Map[String, TriggerInfo],
      config: Config,
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      leverRate: LeverRate,
      entityId: String,
      contractSize: Int
  ) extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  /**
    * status
    */
  final case class Stoped(data: DataStore) extends State

  final case class Idle(data: DataStore) extends State

  final case class Busy(data: DataStore) extends State

  /**
    * command
    */
  trait Command extends BaseSerializer

  final case class Run(
      marketTradeId: String,
      entrustId: String,
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

  final case object Recovery extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case class RunSelfOk() extends Command

  final case class Create(
      orderId: String,
      offset: Offset,
      orderPriceType: OrderPriceType,
      triggerType: TriggerType,
      orderPrice: Double,
      triggerPrice: Double,
      volume: Int
  )(var replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class CreateOk(request: Create) extends Command

  final case class CreateFail(request: Create, status: TriggerCreateFailStatus)
      extends Command

  final case class StreamComplete() extends Command

  final case class Cancel(orderId: String)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  final case class CancelOk(orderId: String) extends Command

  final case class CancelFail(orderId: String, status: TriggerCancelFailStatus)
      extends Command

  final case class Trigger(price: Double) extends Command

  final case class TriggerOk(info: (String, TriggerInfo)) extends Command

  final case class TriggerFail(info: (String, TriggerInfo)) extends Command

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
