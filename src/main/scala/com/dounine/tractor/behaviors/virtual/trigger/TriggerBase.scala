package com.dounine.tractor.behaviors.virtual.trigger

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.TriggerCancelFailStatus.TriggerCancelFailStatus
import com.dounine.tractor.model.types.currency.TriggerStatus.TriggerStatus
import com.dounine.tractor.model.types.currency.TriggerType.TriggerType
import com.dounine.tractor.tools.json.ActorSerializerSuport

import java.time.LocalDateTime

object TriggerBase extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("TriggerBehavior")

  case class TriggerItem(
                          leverRate: LeverRate,
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
                     entrustId: String = EntrustBase.typeKey.name
                   ) extends BaseSerializer

  case class TriggerInfo(
                          trigger: TriggerItem,
                          status: TriggerStatus
                        ) extends BaseSerializer

  case class DataStore(
                        triggers: Map[String, TriggerInfo],
                        config: Config,
                        phone: String,
                        symbol: CoinSymbol,
                        contractType: ContractType,
                        direction: Direction,
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
                        marketTradeId: String = MarketTradeBehavior.typeKey.name,
                        entrustId: String = EntrustBase.typeKey.name
                      ) extends Command

  final case object Recovery extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case class RunSelfOk() extends Command

  final case class Create(
                           orderId: String,
                           leverRate: LeverRate,
                           offset: Offset,
                           orderPriceType: OrderPriceType,
                           triggerType: TriggerType,
                           orderPrice: Double,
                           triggerPrice: Double,
                           volume: Int
                         )(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CreateOk(orderId: String) extends Command

  final case class Cancel(orderId: String)(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CancelOk(orderId: String) extends Command

  final case class CancelFail(orderId: String, status: TriggerCancelFailStatus) extends Command

  final case class Triggers(triggers: Map[String, TriggerInfo]) extends Command

  final case object Ack extends Command

  def createEntityId(phone: String, symbol: CoinSymbol, contractType: ContractType, direction: Direction, randomId: String = ""): String = {
    s"${phone}-${symbol}-${contractType}-${direction}-${randomId}"
  }

}
