package com.dounine.tractor.behaviors.slider

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}
import SliderBase._
import akka.persistence.typed.PersistenceId
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.updown.UpDownBase
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction,
  Offset,
  UpDownStatus
}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SliderBehavior extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(SliderBehavior.getClass)

  def apply(
      entityId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val sharding = ClusterSharding(context.system)
        val materializer = SystemMaterializer(context.system).materializer
        entityId.id.split("\\|").last.split("-") match {
          case Array(
                phone,
                symbolStr,
                contractTypeStr,
                directionStr,
                offsetStr,
                randomId
              ) => {
            val symbol = CoinSymbol.withName(symbolStr)
            val contractType = ContractType.withName(contractTypeStr)
            val direction = Direction.withName(directionStr)
            val offset = Offset.withName(offsetStr)
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
                    idle(data = data.copy(info = info))
                  }

                  def stoped(data: DataStore): Behavior[BaseSerializer] =
                    Behaviors.receiveMessage {
                      case e @ Run(
                            marketTradeId,
                            upDownId,
                            client,
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
                          .throttle(1, 500.milliseconds)
                          .buffer(1, OverflowStrategy.dropHead)
                          .runWith(
                            ActorSink.actorRef(
                              ref = context.self,
                              onCompleteMessage = StreamComplete(),
                              onFailureMessage =
                                e => MarketTradeBehavior.SubFail(e.getMessage)
                            )
                          )(materializer)

//                        timers.startTimerAtFixedRate(
//                          msg = Interval,
//                          interval = 500.milliseconds
//                        )

                        buffer.unstashAll(
                          idle(
                            data = data
                              .copy(
                                info = data.info.copy(
                                  actorRef = Option(client),
                                  maxValue = maxValue
                                )
                              )
                          )
                        )
                      }
                      case e @ _ =>
                        buffer.stash(e)
                        Behaviors.same
                    }

                  def idle(data: DataStore): Behavior[BaseSerializer] =
                    Behaviors.receiveMessage {
                      case MarketTradeBehavior.TradeDetail(
                            _,
                            _,
                            _,
                            price,
                            _,
                            _
                          ) => {
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
                                entrustValue = info.entrustPrice match {
                                  case Some(value) =>
                                    val entrustValue: Double =
                                      middleValue + (value - price)
                                    percentage = entrustValue / info.maxValue
                                    if (
                                      percentage <= 0.1 || percentage >= 0.9
                                    ) {
                                      data.info.actorRef.foreach(actor => {
                                        actor.tell(
                                          msg = PushInfo(
                                            tip =
                                              "The market fluctuation is too large, please pay attention to operation"
                                          )
                                        )
                                      })
                                    }
                                    Option(middleValue + (value - price))
                                  case None => None
                                }
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
                            } else {
                              info.tradeValue match {
                                case Some(value) =>
                                  if (
                                    tradeValue
                                      .formatted("%.1f") != value
                                      .formatted("%.1f")
                                  ) {
                                    sendMessage(
                                      data,
                                      PushDouble(
                                        tradeValue = Option(tradeValue)
                                      ),
                                      offset
                                    )
                                  }
                                case None =>
                              }

                              info = info.copy(
                                tradeValue = Option(tradeValue)
                              )
                            }
                          case None =>
                            info = info.copy(
                              initPrice = Option(price),
                              tradeValue = Option(middleValue)
                            )

                            sendMessage(
                              data = data,
                              message = PushDouble(
                                initPrice = info.initPrice,
                                tradeValue = info.tradeValue,
                                tradePrice = info.tradePrice
                              ),
                              offset
                            )
                        }
                        idle(data = data.copy(info = info))
                      }

                      //              case e @ VirtualOrderTriggerNotifyBehavior.Push(_, notify) => {
                      //                logger.info(e.logJson)
                      //                val price: Double = notify.triggerPrice
                      //                if (notify.offset == data.info.offset) {
                      //                  notify.status match {
                      //                    case TriggerStatus.submit =>
                      //                      handleEntrust(data, price)
                      //                    case _ => Behaviors.same
                      //                  }
                      //                } else Behaviors.same
                      //              }

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
    }
}
