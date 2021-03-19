package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.updown.UpDownBehavior.ShareData
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency.{
  Direction,
  EntrustStatus,
  Offset,
  OrderPriceType,
  TriggerCancelFailStatus,
  TriggerCreateFailStatus,
  TriggerType,
  UpDownStatus,
  UpDownUpdateType
}

import java.util.UUID
import scala.concurrent.duration._

object CloseTriggeringStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(CloseTriggeringStatus.getClass)

  def apply(
      context: ActorContext[BaseSerializer],
      shard: ActorRef[ClusterSharding.ShardCommand],
      timers: TimerScheduler[BaseSerializer],
      shareData: ShareData
  ): (
      (
          State,
          BaseSerializer,
          (State, BaseSerializer) => Effect[BaseSerializer, State]
      ) => Effect[BaseSerializer, State],
      (
          State,
          BaseSerializer,
          (State, BaseSerializer) => State
      ) => State,
      Class[_]
  ) = {
    val sharding = ClusterSharding(context.system)
    val materializer = SystemMaterializer(context.system).materializer
    val pushStatus: (ShareData, UpDownStatus) => Unit = (data, status) => {
      pushInfos(
        data = data,
        infos = Map(
          UpDownUpdateType.status -> status
        ),
        context = context
      )
    }
    val commandHandler: (
        State,
        BaseSerializer,
        (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) => Effect[BaseSerializer, State] = (
        state: State,
        command: BaseSerializer,
        defaultCommand: (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) =>
      command match {
        case Run(_, _, _, _, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Stop() => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              data.info.closeTriggerSubmitOrder match {
                case Some(orderId) =>
                  pushInfos(
                    data = shareData,
                    infos = Map(
                      UpDownUpdateType.status -> UpDownStatus.Stopping,
                      UpDownUpdateType.run -> true,
                      UpDownUpdateType.runLoading -> true
                    ),
                    context = context
                  )
                  Source
                    .future(
                      sharding
                        .entityRefFor(
                          TriggerBase.typeKey,
                          updateState.data.config.triggerId
                        )
                        .ask[BaseSerializer](TriggerBase.Cancel(orderId)(_))(
                          3.seconds
                        )
                    )
                    .runWith(
                      ActorSink.actorRef(
                        ref = context.self,
                        onCompleteMessage = StreamComplete(),
                        onFailureMessage = e => {
                          logger.error(e.logJson)
                          TriggerBase.CancelFail(
                            orderId,
                            TriggerCancelFailStatus.cancelTimeout
                          )
                        }
                      )
                    )(materializer)

                case None =>
                  pushInfos(
                    data = shareData,
                    infos = Map(
                      UpDownUpdateType.status -> UpDownStatus.Stoped,
                      UpDownUpdateType.run -> false,
                      UpDownUpdateType.runLoading -> false
                    ),
                    context = context
                  )
              }
            })
        }
        case EntrustNotifyBehavior.Receive(notif) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              val reverDirection: Direction =
                Direction.reverse(value = data.direction)
              (notif.direction, notif.offset) match {
                case (`reverDirection`, Offset.close) =>
                  notif.entrustStatus match {
                    case EntrustStatus.canceled => //ignore
                    case EntrustStatus.submit => {
                      timers.cancel(triggerName)
                      timers.startSingleTimer(
                        key = entrustTimeoutName,
                        msg = EntrustTimeout(
                          status = notif.entrustStatus,
                          orderId = notif.orderId
                        ),
                        delay = data.info.closeEntrustTimeout
                      )
                      pushStatus(shareData, UpDownStatus.CloseEntrusted)
                    }
                    case EntrustStatus.matchAll => {
                      pushStatus(shareData, UpDownStatus.Closed)
                    }
                    case EntrustStatus.matchPart => {
                      timers.cancel(triggerName)
                      timers.startSingleTimer(
                        key = entrustTimeoutName,
                        msg = EntrustTimeout(
                          status = notif.entrustStatus,
                          orderId = notif.orderId
                        ),
                        delay = data.info.closeEntrustTimeout
                      )
                      pushStatus(shareData, UpDownStatus.ClosePartMatched)
                    }
                    case EntrustStatus.matchPartOtherCancel => {
                      timers.cancel(triggerName)
                      pushStatus(shareData, UpDownStatus.Closed)
                    }
                  }
                case _ => //ignore
              }
            })
        }
        case TriggerBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              context.self.tell(Trigger())
            })
        }
        case TriggerBase.CancelFail(orderId, status) => {
          logger.error(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              status match {
                case TriggerCancelFailStatus.cancelOrderNotExit |
                    TriggerCancelFailStatus.cancelAlreadyCanceled |
                    TriggerCancelFailStatus.cancelAlreadyFailed |
                    TriggerCancelFailStatus.cancelTimeout => {
                  pushStatus(shareData, UpDownStatus.CloseErrored)
                }
                case TriggerCancelFailStatus.cancelAlreadyMatched => {
                  pushStatus(shareData, UpDownStatus.CloseEntrusted)
                }
              }
            })
        }
        case Trigger(handlePrice) => {
          logger.info(command.logJson)
          Effect.none
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              data.info.closeTriggerSubmitOrder match {
                case Some(orderId) =>
                  val triggerPrice: Double = data.direction match {
                    case Direction.sell =>
                      data.tradePrice.get + data.info.closeReboundPrice
                    case Direction.buy =>
                      data.tradePrice.get - data.info.closeReboundPrice
                  }
                  val triggerCancel: () => Unit = () => {
                    Source
                      .future(
                        sharding
                          .entityRefFor(
                            TriggerBase.typeKey,
                            updateState.data.config.triggerId
                          )
                          .ask[BaseSerializer](TriggerBase.Cancel(orderId)(_))(
                            3.seconds
                          )
                      )
                      .runWith(
                        ActorSink.actorRef(
                          ref = context.self,
                          onCompleteMessage = StreamComplete(),
                          onFailureMessage = e => {
                            logger.error(e.logJson)
                            TriggerBase.CancelFail(
                              orderId,
                              TriggerCancelFailStatus.cancelTimeout
                            )
                          }
                        )
                      )(materializer)
                  }
                  if (
                    data.direction == Direction.sell && triggerPrice < data.info.closeTriggerPrice
                  ) {
                    triggerCancel() //price continue down
                  } else if (
                    data.direction == Direction.buy && triggerPrice > data.info.closeTriggerPrice
                  ) {
                    triggerCancel() //price continue up
                  } else {
                    timers.startSingleTimer(
                      key = triggerName,
                      msg = Trigger(),
                      delay = data.info.closeScheduling
                    )
                  }
                case None =>
                  val triggerPrice: Double = data.direction match {
                    case Direction.sell =>
                      data.tradePrice.get + data.info.closeReboundPrice
                    case Direction.buy =>
                      data.tradePrice.get - data.info.closeReboundPrice
                  }

                  val createInfo = TriggerBase.Create(
                    orderId = UUID.randomUUID().toString.replaceAll("-", ""),
                    offset = Offset.close,
                    orderPriceType = OrderPriceType.limit,
                    triggerType = data.direction match {
                      case Direction.sell => TriggerType.ge
                      case Direction.buy  => TriggerType.le
                    },
                    orderPrice = data.direction match {
                      case Direction.sell =>
                        triggerPrice + data.info.closeTriggerPriceSpread
                      case Direction.buy =>
                        triggerPrice - data.info.closeTriggerPriceSpread
                    },
                    triggerPrice = triggerPrice,
                    volume = state.data.info.closeVolume
                  )(null)

                  Source
                    .future(
                      sharding
                        .entityRefFor(
                          TriggerBase.typeKey,
                          state.data.config.triggerId
                        )
                        .ask[BaseSerializer](ref => {
                          createInfo.replyTo = ref
                          createInfo
                        })(3.seconds)
                    )
                    .runWith(
                      ActorSink.actorRef(
                        ref = context.self,
                        onCompleteMessage = StreamComplete(),
                        onFailureMessage = e => {
                          logger.error(e.logJson)
                          TriggerBase.CreateFail(
                            createInfo,
                            TriggerCreateFailStatus.createTimeout
                          )
                        }
                      )
                    )(materializer)
              }
            })
        }
        case TriggerBase.CreateOk(request) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              timers.startSingleTimer(
                key = triggerName,
                msg = Trigger(),
                delay = updateState.data.info.closeScheduling
              )
              pushInfos(
                data = shareData,
                infos = Map(
                  UpDownUpdateType.closeTriggerPrice -> request.triggerPrice
                ),
                context = context
              )
            })
        }
        case TriggerBase.CreateFail(request, status) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              status match {
                case TriggerCreateFailStatus.createTimeout |
                    TriggerCreateFailStatus.createSizeOverflow => {
                  pushStatus(shareData, UpDownStatus.CloseErrored)
                }
                case TriggerCreateFailStatus.createFireTrigger => {
                  context.self.tell(Trigger())
                }
              }
            })
        }
        case EntrustBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect.none
        }
        case EntrustBase.CancelFail(orderId, status) => {
          logger.info(command.logJson)
          Effect.none
        }
        case _ => defaultCommand(state, command)
      }

    val defaultEvent
        : (State, BaseSerializer, (State, BaseSerializer) => State) => State =
      (
          state: State,
          command: BaseSerializer,
          defaultEvent: (State, BaseSerializer) => State
      ) => {
        val data: DataStore = state.data
        command match {

          case Stop() => {
            data.info.closeTriggerSubmitOrder match {
              case Some(orderId) =>
                Stopping(
                  data = data.copy(
                    info = data.info.copy(
                      closeTriggerSubmitOrder = Option.empty,
                      runLoading = false
                    )
                  )
                )
              case None =>
                Stoped(data)
            }
          }

          case EntrustNotifyBehavior.Receive(notif) => {
            val reverDirection: Direction =
              Direction.reverse(value = data.direction)
            (notif.direction, notif.offset) match {
              case (`reverDirection`, Offset.close) =>
                notif.entrustStatus match {
                  case EntrustStatus.canceled => state
                  case EntrustStatus.submit =>
                    CloseEntrusted(
                      data = data.copy(
                        info = data.info.copy(
                          closeTriggerSubmitOrder = Option.empty,
                          closeEntrustSubmitOrder = Option(notif.orderId)
                        )
                      )
                    )
                  case EntrustStatus.matchAll =>
                    Closed(
                      data = data.copy(
                        info = data.info.copy(
                          closeTriggerSubmitOrder = Option.empty,
                          closeEntrustSubmitOrder = Option.empty,
                          closeFee = data.info.closeFee + notif.fee
                        )
                      )
                    )
                  case EntrustStatus.matchPart =>
                    OpenPartMatched(
                      data = data.copy(
                        info = data.info.copy(
                          closeTriggerSubmitOrder = Option.empty,
                          closeEntrustSubmitOrder = Option(notif.orderId),
                          closeFee = data.info.closeFee + notif.fee
                        )
                      )
                    )
                  case EntrustStatus.matchPartOtherCancel =>
                    Closed(
                      data = data.copy(
                        info = data.info.copy(
                          closeTriggerSubmitOrder = Option.empty,
                          closeEntrustSubmitOrder = Option.empty,
                          closeFee = data.info.closeFee + notif.fee
                        )
                      )
                    )
                }
              case _ => state
            }
          }

          case TriggerBase.CancelFail(orderId, status) => {
            status match {
              case TriggerCancelFailStatus.cancelOrderNotExit |
                  TriggerCancelFailStatus.cancelAlreadyCanceled |
                  TriggerCancelFailStatus.cancelTimeout |
                  TriggerCancelFailStatus.cancelAlreadyFailed => {
                CloseErrored(data)
              }
              case TriggerCancelFailStatus.cancelAlreadyMatched => {
                CloseEntrusted(
                  data.copy(
                    info = data.info.copy(
                      closeTriggerSubmitOrder = Option.empty
                    )
                  )
                )
              }
            }
          }

          case TriggerBase.CancelOk(orderId) => {
            CloseTriggering(
              data = data.copy(
                info = data.info.copy(
                  closeTriggerSubmitOrder = Option.empty
                )
              )
            )
          }

          case TriggerBase.CreateOk(request) => {
            CloseTriggering(
              data = data.copy(
                info = data.info.copy(
                  closeTriggerSubmitOrder = Option(request.orderId),
                  closeTriggerPrice = request.triggerPrice
                )
              )
            )
          }

          case TriggerBase.CreateFail(request, status) => {
            status match {
              case TriggerCreateFailStatus.createTimeout |
                  TriggerCreateFailStatus.createSizeOverflow => {
                CloseErrored(data)
              }
              case TriggerCreateFailStatus.createFireTrigger => {
                state
              }
            }
          }

          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[CloseTriggering])
  }
}
