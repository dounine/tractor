package com.dounine.tractor.behaviors.updown

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.stream.{QueueCompletionResult, QueueOfferResult, SourceRef}
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.updown.UpDownBehavior.ShareData
import com.dounine.tractor.behaviors.virtual.entrust.{
  EntrustBase,
  EntrustBehavior
}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.EntrustStatus.EntrustStatus
import com.dounine.tractor.model.types.currency.{
  LeverRate,
  UpDownSubType,
  UpDownUpdateType
}
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency.UpDownSubType.UpDownSubType
import com.dounine.tractor.model.types.currency.UpDownUpdateType.UpDownUpdateType
import com.dounine.tractor.tools.util.CopyUtil

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

object UpDownBase {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("UpDownBehavior")

  sealed trait Command extends BaseSerializer

  final case class Config(
      marketTradeId: String = MarketTradeBehavior.typeKey.name,
      entrustId: String = EntrustBase.typeKey.name,
      triggerId: String = TriggerBase.typeKey.name,
      entrustNotifyId: String = EntrustNotifyBehavior.typeKey.name,
      aggregationId: String = AggregationBehavior.typeKey.name
  ) extends BaseSerializer

  final case class UserInfo(
      accessKey: Option[String] = Option.empty,
      accessSecret: Option[String] = Option.empty
  ) extends BaseSerializer

  final case class Info(
      run: Boolean = false,
      runLoading: Boolean = false,
      openTriggerPrice: BigDecimal = 0,
      openReboundPrice: BigDecimal = 0,
      openTriggerPriceSpread: BigDecimal = 0,
      openVolume: Int = 1,
      openEntrustTimeout: FiniteDuration = 3.seconds,
      openScheduling: FiniteDuration = 10.seconds,
      openTriggerSubmitOrder: Option[String] = Option.empty,
      openEntrustSubmitOrder: Option[String] = Option.empty,
      openAvgPrice: BigDecimal = 0,
      openFee: BigDecimal = 0,
      closeZoom: Boolean = true,
      closeTriggerPrice: BigDecimal = 0,
      closeReboundPrice: BigDecimal = 0,
      closeTriggerPriceSpread: BigDecimal = 0,
      closeVolume: Int = 0,
      closeGetInProfit: BigDecimal = 0,
      closeEntrustTimeout: FiniteDuration = 3.seconds,
      closeScheduling: FiniteDuration = 500.milliseconds,
      closeTriggerSubmitOrder: Option[String] = Option.empty,
      closeEntrustSubmitOrder: Option[String] = Option.empty,
      closeFee: BigDecimal = 0,
      closeProfit: BigDecimal = 0
  ) extends BaseSerializer

  final case class DataStore(
      tradePrice: Option[BigDecimal] = Option.empty,
      preTradePrice: Option[BigDecimal] = Option.empty,
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      leverRate: LeverRate = LeverRate.x20,
      info: Info = Info(),
      config: Config = Config(),
      userInfo: UserInfo
  ) extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Inited(data: DataStore) extends State

  final case class UnHealth(data: DataStore) extends State

  final case class Stoped(data: DataStore) extends State

  final case class Stopping(data: DataStore) extends State

  final case class OpenTriggering(data: DataStore) extends State

  final case class OpenPartMatched(data: DataStore) extends State

  final case class OpenEntrusted(data: DataStore) extends State

  final case class Opened(data: DataStore) extends State

  final case class OpenErrored(data: DataStore) extends State

  final case class CloseTriggering(data: DataStore) extends State

  final case class ClosePartMatched(data: DataStore) extends State

  final case class CloseEntrusted(data: DataStore) extends State

  final case class Closed(data: DataStore) extends State

  final case class CloseErrored(data: DataStore) extends State

  final case class Stop() extends Command

  final case class Shutdown() extends Command

  final case class Query()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class QuerySuccess(status: UpDownStatus, info: DataStore)
      extends Command

  final case class Run(
      marketTradeId: String = MarketTradeBehavior.typeKey.name,
      entrustId: String = EntrustBase.typeKey.name,
      triggerId: String = TriggerBase.typeKey.name,
      entrustNotifyId: String = EntrustNotifyBehavior.typeKey.name,
      aggregationId: String = AggregationBehavior.typeKey.name
  ) extends Command

  final case class Trigger(
      handPrice: Option[BigDecimal] = Option.empty
  ) extends Command

  final case class Recovery() extends Command

  final case class StreamComplete() extends Command

  final case class PushInfo(
      run: Option[Boolean] = Option.empty,
      runLoading: Option[Boolean] = Option.empty,
      status: Option[UpDownStatus] = Option.empty,
      openTriggerPrice: Option[BigDecimal] = Option.empty,
      openReboundPrice: Option[BigDecimal] = Option.empty,
      openTriggerPriceSpread: Option[BigDecimal] = Option.empty,
      openVolume: Option[Int] = Option.empty,
      openEntrustTimeout: Option[FiniteDuration] = Option.empty,
      openScheduling: Option[FiniteDuration] = Option.empty,
      openLeverRate: Option[LeverRate] = Option.empty,
      openFee: Option[BigDecimal] = Option.empty,
      closeStatus: Option[UpDownStatus] = Option.empty,
      closeZoom: Option[Boolean] = Option.empty,
      closeTriggerPrice: Option[BigDecimal] = Option.empty,
      closeReboundPrice: Option[BigDecimal] = Option.empty,
      closeTriggerPriceSpread: Option[BigDecimal] = Option.empty,
      closeVolume: Option[Int] = Option.empty,
      closeGetInProfit: Option[BigDecimal] = Option.empty,
      closeEntrustTimeout: Option[FiniteDuration] = Option.empty,
      closeScheduling: Option[FiniteDuration] = Option.empty,
      closeFee: Option[BigDecimal] = Option.empty,
      closeProfit: Option[BigDecimal] = Option.empty
  ) extends BaseSerializer

  final case class PushDataInfo(info: PushInfo) extends Command

  final case class EntrustTimeout(
      status: EntrustStatus,
      orderId: String
  ) extends Command

  case class Sub(`type`: UpDownSubType = UpDownSubType.all)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  case class SubOk(source: SourceRef[PushDataInfo]) extends Command

  case class SubFail(msg: String) extends Command

  case class Update(
      name: UpDownUpdateType,
      value: Any,
      replyTo: ActorRef[BaseSerializer]
  ) extends Command

  final case class UpdateOk() extends Command

  final case class UpdateFail(msg: String) extends Command

  def createEntityId(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      randomId: String = ""
  ): String = {
    s"${phone}-${symbol}-${contractType}-${direction}-${randomId}"
  }

  final val triggerName: String = "Trigger"
  final val entrustTimeoutName: String = "EntrustTimeout"

  def pushInfos(
      data: ShareData,
      infos: Map[UpDownUpdateType, Any],
      context: ActorContext[BaseSerializer]
  ): Unit = {
    val info = PushDataInfo(
      info = infos.foldLeft(PushInfo())((sum, next) => {
        CopyUtil.copy[PushInfo](sum)(
          values = Map(next._1.toString -> Option(next._2))
        )
      })
    )
    data.infoQueue
      .offer(info)
      .foreach {
        case result: QueueCompletionResult =>
        case QueueOfferResult.Enqueued     =>
        case QueueOfferResult.Dropped      =>
      }(context.executionContext)
  }

}
