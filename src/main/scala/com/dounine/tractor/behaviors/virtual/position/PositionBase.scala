package com.dounine.tractor.behaviors.virtual.position

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.behaviors.MarketTradeBehavior
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
                                 leverRate: LeverRate,
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
                           marketTradeId: String = MarketTradeBehavior.typeKey.name
                         ) extends BaseSerializer

  case class DataStore(
                        positions: Map[(Offset, Direction), PositionInfo],
                        config: Config,
                        phone: String,
                        symbol: CoinSymbol,
                        contractType: ContractType,
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
                        marketTradeId: String = MarketTradeBehavior.typeKey.name
                      ) extends Command

  final case class RunSelfOk() extends Command

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case object Recovery extends Command

  final case class Create(
                           offset: Offset,
                           direction: Direction,
                           leverRate: LeverRate,
                           volume: Int,
                           latestPrice: Double
                         )(val replyTo: ActorRef[BaseSerializer]) extends Command

  final case class CreateFail(status: PositionCreateFailStatus) extends Command

  final case class OpenOk() extends Command

  final case class MergeOk() extends Command

  final case class CloseOk() extends Command

  final case class NewPosition(
                                offset: Offset,
                                direction: Direction,
                                position: PositionInfo
                              ) extends Command

  final case class MergePosition(
                                  offset: Offset,
                                  direction: Direction,
                                  position: PositionInfo
                                ) extends Command

  final case class RemovePosition(
                                   offset: Offset,
                                   direction: Direction
                                 ) extends Command

}
