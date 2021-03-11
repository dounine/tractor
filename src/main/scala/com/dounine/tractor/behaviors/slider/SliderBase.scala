package com.dounine.tractor.behaviors.slider

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.Offset.Offset

import java.util.UUID

object SliderBase {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer](s"SliderBehavior")

  sealed trait Command extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Stoped(data: DataStore) extends State

  final case class Idle(data: DataStore) extends State

  final case class Busy(data: DataStore) extends State

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case object Interval extends Command

  final case class StreamComplete() extends Command

  final case class Run(
      marketTradeId: String,
      upDownId: String,
      client: ActorRef[BaseSerializer],
      maxValue: Double
  ) extends Command

  final case class RunSelfOk(maxValue: Double) extends Command

  final case object Recovery extends Command

  final case class Push(
      offset: Offset,
      initPrice: Option[String] = Option.empty,
      entrustValue: Option[String] = Option.empty,
      entrustPrice: Option[String] = Option.empty,
      tradeValue: Option[String] = Option.empty,
      tradePrice: Option[String] = Option.empty
  ) extends BaseSerializer

  final case class PushInfo(
      tip: String
  ) extends BaseSerializer

  final case class PushDouble(
      initPrice: Option[Double] = Option.empty,
      entrustValue: Option[Double] = Option.empty,
      entrustPrice: Option[Double] = Option.empty,
      tradeValue: Option[Double] = Option.empty,
      tradePrice: Option[Double] = Option.empty
  ) extends BaseSerializer

  final case class Info(
      maxValue: Double,
      initPrice: Option[Double] = Option.empty,
      entrustValue: Option[Double] = Option.empty,
      entrustPrice: Option[Double] = Option.empty,
      tradeValue: Option[Double] = Option.empty,
      tradePrice: Option[Double] = Option.empty,
      actorRef: Option[ActorRef[BaseSerializer]] = Option.empty,
      price: Option[Double] = Option.empty
  )

  final case class DataStore(info: Info) extends BaseSerializer

  def createId(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      offset: Offset,
      uuid: String
  ): String = {
    s"""${phone}-${symbol}-${contractType}-${direction}-${offset}-${uuid}"""
  }

  def sendMessage(
      data: DataStore,
      message: PushDouble,
      offset: Offset
  ): Unit = {
    data.info.actorRef.foreach(ref => {
      ref.tell(
        Push(
          offset = offset,
          initPrice = message.initPrice.map(_.formatted("%.1f")),
          entrustPrice = message.entrustPrice.map(_.formatted("%.1f")),
          entrustValue = message.entrustValue.map(_.formatted("%.1f")),
          tradePrice = message.tradePrice.map(_.formatted("%.1f")),
          tradeValue = message.tradeValue.map(_.formatted("%.1f"))
        )
      )
    })
  }
  def handleEntrust(data: DataStore, price: Double, offset: Offset): State = {
    var info: Info =
      data.info.copy(entrustPrice = Option(price))
    val middleValue: Double = info.maxValue / 2
    info.initPrice match {
      case Some(initPrice) =>
        val entrustValue: Double =
          middleValue + (price - initPrice)
        val percentage: Double =
          entrustValue / info.maxValue
        if (percentage <= 0.1 || percentage >= 0.9) {
          info = info.copy(
            initPrice = Option(price),
            entrustValue = Option(middleValue),
            entrustPrice = Option(price),
            tradeValue = info.tradePrice match {
              case Some(value) =>
                Option(
                  middleValue + (value - price)
                )
              case None => None
            }
          )
          sendMessage(
            data = data,
            message = PushDouble(
              initPrice = info.initPrice,
              tradeValue = info.tradeValue,
              entrustValue = info.entrustValue
            ),
            offset
          )
        } else {
          info = info.copy(
            entrustValue = Option(entrustValue),
            entrustPrice = Option(price)
          )

          sendMessage(
            data = data,
            message = PushDouble(
              entrustValue = info.entrustValue
            ),
            offset
          )
        }
      case None =>
        info = info.copy(
          initPrice = Option(price),
          entrustValue = Option(middleValue),
          entrustPrice = Option(price)
        )
        sendMessage(
          data,
          PushDouble(
            initPrice = info.initPrice,
            tradeValue = info.tradeValue,
            entrustValue = info.entrustValue
          ),
          offset
        )
    }
    Idle(data.copy(info = info))
  }
}
