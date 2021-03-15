package com.dounine.tractor.behaviors.slider

import akka.NotUsed
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{
  ActorContext,
  Behaviors,
  StashBuffer,
  TimerScheduler
}
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  EntityRef,
  EntityTypeKey
}
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}
import akka.stream.{OverflowStrategy, SourceRef, SystemMaterializer}
import akka.stream.scaladsl.{BroadcastHub, Source, StreamRefs}
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.updown.UpDownBase
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction,
  Offset,
  UpDownStatus,
  UpDownSubType
}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SliderBehavior extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer](s"SliderBehavior")

  sealed trait Command extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Stoped(data: DataStore) extends State

  final case class Idle(data: DataStore) extends State

  final case object Stop extends Command

  final case object Shutdown extends Command

  final case class StreamComplete() extends Command

  case class Sub()(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  case class SubOk(source: SourceRef[Push]) extends Command

  case class SubFail(msg: String) extends Command

  final case class Run(
      marketTradeId: String,
      upDownId: Option[String],
      maxValue: Double
  ) extends Command

  final case class Push(
      initPrice: Option[String] = Option.empty,
      entrustValue: Option[String] = Option.empty,
      entrustPrice: Option[String] = Option.empty,
      tradeValue: Option[String] = Option.empty,
      tradePrice: Option[String] = Option.empty
  ) extends BaseSerializer

  final case class Info(
      maxValue: Double,
      initPrice: Option[Double] = Option.empty,
      entrustValue: Option[Double] = Option.empty,
      entrustPrice: Option[Double] = Option.empty,
      tradeValue: Option[Double] = Option.empty,
      tradePrice: Option[Double] = Option.empty,
      price: Option[Double] = Option.empty
  )

  final case class DataStore(info: Info) extends BaseSerializer

  private final val logger: Logger =
    LoggerFactory.getLogger(SliderBehavior.getClass)

  def apply(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      offset: Offset
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      {
        val sharding = ClusterSharding(context.system)
        val materializer = SystemMaterializer(context.system).materializer
        val config = context.system.settings.config.getConfig("app")
        val (pushQueue, infoSource) = Source
          .queue[Push](
            100,
            OverflowStrategy.dropHead
          )
          .preMaterialize()(materializer)
        val pushBrocastHub =
          infoSource.runWith(BroadcastHub.sink)(materializer)

        val formatStyle = "%.1f"

        Behaviors.withTimers { timers: TimerScheduler[BaseSerializer] =>
          Behaviors.withStash[BaseSerializer](100) {
            buffer: StashBuffer[BaseSerializer] =>
              def handleEntrust(
                  data: DataStore,
                  price: Double
              ): Behavior[BaseSerializer] = {
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
                      pushQueue.offer(
                        Push(
                          initPrice =
                            info.initPrice.map(_.formatted(formatStyle)),
                          entrustValue =
                            info.entrustValue.map(_.formatted(formatStyle)),
                          tradeValue =
                            info.tradeValue.map(_.formatted(formatStyle))
                        )
                      )
                    } else {
                      info = info.copy(
                        entrustValue = Option(entrustValue),
                        entrustPrice = Option(price)
                      )
                      pushQueue.offer(
                        Push(
                          entrustValue =
                            info.entrustValue.map(_.formatted(formatStyle))
                        )
                      )
                    }
                  case None =>
                    info = info.copy(
                      initPrice = Option(price),
                      entrustValue = Option(middleValue),
                      entrustPrice = Option(price)
                    )
                    pushQueue.offer(
                      Push(
                        initPrice =
                          info.initPrice.map(_.formatted(formatStyle)),
                        tradeValue =
                          info.tradeValue.map(_.formatted(formatStyle)),
                        entrustValue =
                          info.entrustValue.map(_.formatted(formatStyle))
                      )
                    )
                }
                idle(data = data.copy(info = info))
              }

              def stoped(data: DataStore): Behavior[BaseSerializer] =
                Behaviors.receiveMessage {
                  case e @ Run(
                        marketTradeId,
                        upDownId,
                        maxValue
                      ) => {
                    logger.info(e.logJson)
                    Source
                      .future(
                        sharding
                          .entityRefFor(
                            MarketTradeBehavior.typeKey,
                            marketTradeId
                          )
                          .ask[BaseSerializer](
                            MarketTradeBehavior.Sub(symbol, contractType)(_)
                          )(3.seconds)
                      )
                      .flatMapConcat {
                        case MarketTradeBehavior.SubOk(source) => source
                      }
                      .throttle(
                        1,
                        config
                          .getDuration("engine.slider.speed")
                          .toMillis
                          .milliseconds
                      )
                      .buffer(2, OverflowStrategy.dropHead)
                      .runWith(
                        ActorSink.actorRef(
                          ref = context.self,
                          onCompleteMessage = StreamComplete(),
                          onFailureMessage =
                            e => MarketTradeBehavior.SubFail(e.getMessage)
                        )
                      )(materializer)

                    upDownId.foreach(updown => {
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              UpDownBase.typeKey,
                              updown
                            )
                            .ask[BaseSerializer](
                              UpDownBase.Sub(`type` = UpDownSubType.trigger)(_)
                            )(3.seconds)
                        )
                        .flatMapConcat {
                          case UpDownBase.SubOk(source) => source
                        }
                        .runWith(
                          ActorSink.actorRef(
                            ref = context.self,
                            onCompleteMessage = StreamComplete(),
                            onFailureMessage =
                              e => UpDownBase.SubFail(e.getMessage)
                          )
                        )(materializer)
                    })

                    buffer.unstashAll(
                      idle(
                        data = data
                          .copy(
                            info = data.info.copy(
                              maxValue = maxValue
                            )
                          )
                      )
                    )
                  }
                  case e @ Sub() => {
                    logger.info(e.logJson)
                    val sourceRef: SourceRef[Push] =
                      Source
                        .single(
                          Push(
                            initPrice =
                              data.info.initPrice.map(_.formatted(formatStyle)),
                            tradePrice = data.info.tradePrice
                              .map(_.formatted(formatStyle)),
                            tradeValue = data.info.tradeValue
                              .map(_.formatted(formatStyle)),
                            entrustPrice = data.info.entrustPrice
                              .map(_.formatted(formatStyle)),
                            entrustValue = data.info.entrustValue
                              .map(_.formatted(formatStyle))
                          )
                        )
                        .concat(pushBrocastHub)
                        .runWith(StreamRefs.sourceRef())(materializer)
                    e.replyTo.tell(SubOk(sourceRef))
                    Behaviors.same
                  }
                  case e @ _ => {
                    buffer.stash(e)
                    Behaviors.same
                  }
                }

              def idle(data: DataStore): Behavior[BaseSerializer] =
                Behaviors.receiveMessage {
                  case Run(_, _, _) => {
                    Behaviors.same
                  }
                  case UpDownBase.PushDataInfo(info) => {
                    logger.info(info.logJson)
                    offset match {
                      case Offset.open => {
                        if (info.openTriggerPrice.get == 0) {
                          Behaviors.same
                        } else handleEntrust(data, info.openTriggerPrice.get)
                      }
                      case Offset.close => {
                        if (info.closeTriggerPrice.get == 0) {
                          Behaviors.same
                        } else handleEntrust(data, info.openTriggerPrice.get)
                      }
                    }
                  }
                  case e @ MarketTradeBehavior.TradeDetail(
                        _,
                        _,
                        _,
                        price,
                        _,
                        _
                      ) => {
                    logger.info(e.logJson)
                    var info: Info =
                      data.info.copy(tradePrice = Option(price))
                    val middleValue: Double = info.maxValue / 2
                    info.initPrice match {
                      case Some(initPrice) =>
                        val tradeValue: Double =
                          middleValue + (price - initPrice)
                        var percentage: Double =
                          tradeValue / info.maxValue

                        if (percentage <= 0.1 || percentage >= 0.9) {
                          info = info.copy(
                            initPrice = Option(price),
                            tradeValue = Option(middleValue),
                            entrustValue = info.entrustPrice.map(value => {
                              middleValue + (value - price)
                            })
                          )
                          pushQueue.offer(
                            Push(
                              initPrice =
                                info.initPrice.map(_.formatted(formatStyle)),
                              tradeValue =
                                info.tradeValue.map(_.formatted(formatStyle)),
                              entrustValue =
                                info.entrustValue.map(_.formatted(formatStyle))
                            )
                          )
                        } else {
                          info.tradeValue.foreach(value => {
                            if (
                              tradeValue
                                .formatted(formatStyle) != value
                                .formatted(formatStyle)
                            ) {
                              pushQueue.offer(
                                Push(
                                  tradeValue =
                                    Option(tradeValue.formatted(formatStyle))
                                )
                              )
                            }
                          })
                          info = info.copy(
                            tradeValue = Option(tradeValue)
                          )
                        }
                      case None =>
                        info = info.copy(
                          initPrice = Option(price),
                          tradeValue = Option(middleValue)
                        )
                        pushQueue.offer(
                          Push(
                            initPrice =
                              info.initPrice.map(_.formatted(formatStyle)),
                            tradeValue =
                              info.tradeValue.map(_.formatted(formatStyle)),
                            tradePrice =
                              info.tradePrice.map(_.formatted(formatStyle))
                          )
                        )
                    }
                    idle(data = data.copy(info = info))
                  }

                  case e @ UpDownBase.QuerySuccess(status, info) => {
                    logger.info(e.logJson)
                    offset match {
                      case Offset.open =>
                        if (status == UpDownStatus.Closed) {
                          idle(
                            data = data.copy(
                              info = data.info.copy(
                                entrustPrice = Option.empty,
                                entrustValue = Option.empty
                              )
                            )
                          )
                        } else if (status != UpDownStatus.Stoped) {
                          if (info.info.openTriggerPrice == 0) {
                            Behaviors.same
                          } else
                            handleEntrust(data, info.info.openTriggerPrice)
                        } else Behaviors.same
                      case Offset.close =>
                        if (status == UpDownStatus.Closed) {
                          idle(
                            data = data.copy(
                              info = data.info.copy(
                                entrustPrice = Option.empty,
                                entrustValue = Option.empty
                              )
                            )
                          )
                        } else if (status != UpDownStatus.Stoped) {
                          if (info.info.closeTriggerPrice == 0) {
                            Behaviors.same
                          } else
                            handleEntrust(data, info.info.closeTriggerPrice)
                        } else Behaviors.same
                    }
                  }
                  case e @ Sub() => {
                    logger.info(e.logJson)
                    val isDefine =
                      data.info.initPrice.isDefined || data.info.tradePrice.isDefined || data.info.tradeValue.isDefined || data.info.entrustPrice.isDefined || data.info.entrustValue.isDefined
                    val sourceRef: SourceRef[Push] = {
                      (if (isDefine) {
                         Source
                           .single(
                             Push(
                               initPrice = data.info.initPrice
                                 .map(_.formatted(formatStyle)),
                               tradePrice = data.info.tradePrice
                                 .map(_.formatted(formatStyle)),
                               tradeValue = data.info.tradeValue
                                 .map(_.formatted(formatStyle)),
                               entrustPrice = data.info.entrustPrice
                                 .map(_.formatted(formatStyle)),
                               entrustValue = data.info.entrustValue
                                 .map(_.formatted(formatStyle))
                             )
                           )
                       } else {
                         Source.empty[Push]
                       })
                        .concat(pushBrocastHub)
                        .runWith(StreamRefs.sourceRef())(materializer)
                    }
                    e.replyTo.tell(SubOk(sourceRef))
                    Behaviors.same
                  }
                  case e @ Shutdown => {
                    logger.info(e.logJson)
                    Behaviors.stopped
                  }
                }

              stoped(data =
                DataStore(
                  info = Info(
                    maxValue = -1
                  )
                )
              )
          }
        }
      }
    }
}
