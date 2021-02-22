package com.dounine.tractor.behaviors.virtual

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.model.models.{BaseSerializer, TriggerModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.TriggerStatus.TriggerStatus
import com.dounine.tractor.model.types.currency.TriggerType.TriggerType
import com.dounine.tractor.tools.json.ActorSerializerSuport

import java.time.LocalDateTime

object TriggerBase extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("TriggerBehavior")

  case class TriggerItem(
                          orderId: String,
                          direction: Direction,
                          leverRate: LeverRate,
                          offset: Offset,
                          orderPriceType: OrderPriceType,
                          triggerType: TriggerType,
                          orderPrice: Double,
                          triggerPrice: Double,
                          volume: Int,
                          time: LocalDateTime
                        )

  case class TriggerInfo(
                          info: TriggerItem,
                          status: TriggerStatus
                        ) extends BaseSerializer

  case class DataStore(
                        triggers: Map[String, TriggerInfo],
                        phone: String,
                        symbol: CoinSymbol,
                        contractType: ContractType
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

  final case object Run extends Command

  final case object Recovery extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case class RunSelfOk() extends Command

  final case class Create(
                           orderId: String,
                           direction: Direction,
                           leverRate: LeverRate,
                           offset: Offset,
                           orderPriceType: OrderPriceType,
                           triggerType: TriggerType,
                           orderPrice: Double,
                           triggerPrice: Double,
                           volume: Int
                         )(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CreateOk(orderId: String) extends Command

  def createEntityId(phone: String, symbol: CoinSymbol, contractType: ContractType): String = {
    s"${phone}-${symbol}-${contractType}"
  }

}
