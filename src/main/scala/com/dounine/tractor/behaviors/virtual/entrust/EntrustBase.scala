package com.dounine.tractor.behaviors.virtual.entrust

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.position.PositionBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.EntrustCancelFailStatus.EntrustCancelFailStatus
import com.dounine.tractor.model.types.currency.EntrustStatus.EntrustStatus
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.tools.json.ActorSerializerSuport

import java.time.LocalDateTime

object EntrustBase extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("EntrustBehavior")

  final case class EntrustItem(
                                leverRate: LeverRate,
                                offset: Offset,
                                orderPriceType: OrderPriceType,
                                price: Double,
                                marginFrozen: Double,
                                volume: Int,
                                time: LocalDateTime
                              ) extends BaseSerializer

  final case class Config(
                           marketTradeId: String = MarketTradeBehavior.typeKey.name,
                           positionId: String = PositionBase.typeKey.name
                         ) extends BaseSerializer

  final case class EntrustInfo(
                                entrust: EntrustItem,
                                status: EntrustStatus
                              ) extends BaseSerializer

  case class DataStore(
                        entrusts: Map[String, EntrustInfo],
                        config: Config,
                        phone: String,
                        symbol: CoinSymbol,
                        contractType: ContractType,
                        direction: Direction,
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
                        marketTradeId: String = MarketTradeBehavior.typeKey.name,
                        positionId: String = PositionBase.typeKey.name
                      ) extends Command

  final case class RunSelfOk() extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case object Recovery extends Command

  final case class Create(
                           orderId: String,
                           leverRate: LeverRate,
                           offset: Offset,
                           orderPriceType: OrderPriceType,
                           price: Double,
                           volume: Int
                         )(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CreateOk(orderId: String) extends Command

  final case class Cancel(orderId: String)(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CancelOk(orderId: String) extends Command

  final case class CancelFail(orderId: String, status: EntrustCancelFailStatus) extends Command

  final case class Entrusts(
                             entrusts: Map[String, EntrustInfo]
                           ) extends Command

  def createEntityId(phone: String, symbol: CoinSymbol, contractType: ContractType, direction: Direction, randomId: String = ""): String = {
    s"${phone}-${symbol}-${contractType}-${direction}-${randomId}"
  }

}
