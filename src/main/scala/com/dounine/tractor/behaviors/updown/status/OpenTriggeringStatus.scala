package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.updown.UpDownBehavior.ShareData
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency.{
  Direction,
  EntrustCancelFailStatus,
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
import scala.util.{Failure, Success}

object OpenTriggeringStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(OpenTriggeringStatus.getClass)

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
    val sharding: ClusterSharding = ClusterSharding(context.system)
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
    ) => {
      command match {
        case Run(_, _, _, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Stop() => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              timers.cancel(triggerName)
              timers.cancel(entrustTimeoutName)
              data.info.openTriggerSubmitOrder match {
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
              (notif.direction, notif.offset) match {
                case (data.direction, Offset.open) =>
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
                        delay = data.info.openEntrustTimeout
                      )
                      pushStatus(shareData, UpDownStatus.OpenEntrusted)
                    }
                    case EntrustStatus.matchAll => {
                      context.self.tell(Trigger())
                      timers.cancel(entrustTimeoutName)
                      pushStatus(shareData, UpDownStatus.Opened)
                    }
                    case EntrustStatus.matchPart => {
                      timers.cancel(triggerName)
                      timers.startSingleTimer(
                        key = entrustTimeoutName,
                        msg = EntrustTimeout(
                          status = EntrustStatus.matchPart,
                          orderId = notif.orderId
                        ),
                        delay = data.info.openEntrustTimeout
                      )
                      pushStatus(shareData, UpDownStatus.OpenPartMatched)
                    }
                    case EntrustStatus.matchPartOtherCancel => {
                      timers.cancel(triggerName)
                      timers.cancel(entrustTimeoutName)
                      pushStatus(shareData, UpDownStatus.Opened)
                    }
                  }
                case _ =>
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
              pushStatus(shareData, UpDownStatus.OpenErrored)
            })
        }

        case Trigger(handlePrice) => {
          logger.info(command.logJson)
          Effect.none
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              if (data.tradePrice.isEmpty) {
                timers.startSingleTimer(
                  key = triggerName,
                  msg = Trigger(),
                  delay = 1.seconds
                )
              } else {
                data.info.openTriggerSubmitOrder match {
                  case Some(orderId) => {
                    Source
                      .future(
                        sharding
                          .entityRefFor(
                            TriggerBase.typeKey,
                            state.data.config.triggerId
                          )
                          .ask[BaseSerializer](
                            TriggerBase.Cancel(
                              orderId
                            )(_)
                          )(3.seconds)
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
                  case None =>
                    val triggerPriceDouble: Double =
                      data.direction match {
                        case Direction.sell =>
                          data.tradePrice.get - data.info.openReboundPrice
                        case Direction.buy =>
                          data.tradePrice.get + data.info.openReboundPrice
                      }
                    val triggerPrice: Double =
                      handlePrice.getOrElse(triggerPriceDouble)

                    val createInfo = TriggerBase.Create(
                      orderId = UUID.randomUUID().toString.replaceAll("-", ""),
                      offset = Offset.open,
                      orderPriceType = OrderPriceType.limit,
                      triggerType = data.direction match {
                        case Direction.sell => TriggerType.le
                        case Direction.buy  => TriggerType.ge
                      },
                      orderPrice = data.direction match {
                        case Direction.sell =>
                          triggerPrice - data.info.openTriggerPriceSpread
                        case Direction.buy =>
                          triggerPrice + data.info.openTriggerPriceSpread
                      },
                      triggerPrice = triggerPrice,
                      volume = state.data.info.openVolume
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
              }
            })
        }

        case EntrustBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect.none
            .thenRun((updateState: State) => {
              context.self.tell(Trigger())
              pushInfos(
                data = shareData,
                infos = Map(
                  UpDownUpdateType.status -> UpDownStatus.OpenTriggering
                ),
                context = context
              )
            })
        }
        case EntrustBase.CancelFail(orderId, status) => {
          logger.info(command.logJson)
          status match {
            case EntrustCancelFailStatus.cancelOrderNotExit |
                EntrustCancelFailStatus.cancelAlreadyCanceled |
                EntrustCancelFailStatus.cancelAlreadyFailed |
                EntrustCancelFailStatus.cancelTimeout => {
              Effect
                .persist(command)
                .thenRun((updateState: State) => {
                  pushInfos(
                    data = shareData,
                    infos = Map(
                      UpDownUpdateType.status -> UpDownStatus.OpenErrored
                    ),
                    context = context
                  )
                })
            }
            case EntrustCancelFailStatus.cancelAlreadyMatchAll |
                EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {

              /**
                * convert to OpenEntrusted
                * but must receive message matchAll or matchPart from EntrustNotifyBehavior.Receive
                */
              Effect
                .persist(command)
                .thenRun((updateState: State) => {
                  pushInfos(
                    data = shareData,
                    infos = Map(
                      UpDownUpdateType.status -> UpDownStatus.OpenEntrusted
                    ),
                    context = context
                  )
                })
            }
          }
        }
        case e @ TriggerBase.CreateOk(orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              timers.startSingleTimer(
                key = triggerName,
                msg = Trigger(),
                delay = updateState.data.info.openScheduling
              )
              pushInfos(
                data = shareData,
                infos = Map(
                  UpDownUpdateType.openTriggerPrice -> e.request.triggerPrice
                ),
                context = context
              )
            })
        }
        case TriggerBase.CreateFail(request, status) => {
          logger.error(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              status match {
                case TriggerCreateFailStatus.createTimeout => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
                case TriggerCreateFailStatus.createFireTrigger => {
                  timers.startSingleTimer(
                    key = triggerName,
                    msg = Trigger(),
                    delay = 1.seconds
                  )
                }
              }
            })
        }

        case _ => defaultCommand(state, command)
      }
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
            data.info.openTriggerSubmitOrder match {
              case Some(_) =>
                Stopping(
                  data = data.copy(
                    info = data.info.copy(
                      openTriggerSubmitOrder = Option.empty,
                      runLoading = true
                    )
                  )
                )
              case None =>
                Stoped(data)
            }
          }

          case EntrustNotifyBehavior.Receive(notif) => {
            (notif.direction, notif.offset) match {
              case (data.direction, Offset.open) =>
                notif.entrustStatus match {
                  case EntrustStatus.canceled => state
                  case EntrustStatus.submit =>
                    OpenEntrusted(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
                          openEntrustSubmitOrder = Option(notif.orderId)
                        )
                      )
                    )
                  case EntrustStatus.matchAll =>
                    Opened(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
                          openEntrustSubmitOrder = Option.empty,
                          openFee = data.info.openFee + notif.fee,
                          closeVolume = data.info.closeVolume + notif.volume
                        )
                      )
                    )
                  case EntrustStatus.matchPart =>
                    OpenPartMatched(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
                          openEntrustSubmitOrder = Option(notif.orderId),
                          closeVolume = data.info.closeVolume + notif.volume,
                          openFee = data.info.openFee + notif.fee
                        )
                      )
                    )
                  case EntrustStatus.matchPartOtherCancel =>
                    Opened(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
                          openEntrustSubmitOrder = Option.empty,
                          openFee = data.info.openFee + notif.fee,
                          closeVolume = data.info.closeVolume + notif.volume
                        )
                      )
                    )
                }
              case _ => state
            }
          }

          case TriggerBase.CancelOk(orderId) => {
            OpenTriggering(
              data = data.copy(
                info = data.info.copy(
                  openTriggerSubmitOrder = Option.empty
                )
              )
            )
          }

          case TriggerBase.CancelFail(orderId, status) => {
            status match {
              case TriggerCancelFailStatus.cancelOrderNotExit =>
                OpenErrored(data)
              case TriggerCancelFailStatus.cancelAlreadyCanceled => state
              case TriggerCancelFailStatus.cancelAlreadyMatched =>
                OpenEntrusted(data)
              case TriggerCancelFailStatus.cancelAlreadyFailed =>
                OpenErrored(data)
              case TriggerCancelFailStatus.cancelTimeout => OpenErrored(data)
            }
          }

          case EntrustBase.CancelOk(orderId) => {
            OpenTriggering(
              data = data.copy(
                info = data.info.copy(
                  openEntrustSubmitOrder = Option.empty
                )
              )
            )
          }

          case EntrustBase.CancelFail(orderId, status) => {
            status match {
              case EntrustCancelFailStatus.cancelOrderNotExit |
                  EntrustCancelFailStatus.cancelAlreadyCanceled |
                  EntrustCancelFailStatus.cancelAlreadyFailed |
                  EntrustCancelFailStatus.cancelTimeout => {
                OpenErrored(
                  data = data.copy(
                    info = data.info.copy(
                      openEntrustSubmitOrder = Option.empty
                    )
                  )
                )
              }
              case EntrustCancelFailStatus.cancelAlreadyMatchAll |
                  EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {
                OpenEntrusted(
                  data = data.copy(
                    info = data.info.copy(
                      openEntrustSubmitOrder = Option.empty
                    )
                  )
                )
              }
            }
          }

          case TriggerBase.CreateOk(request) => {
            OpenTriggering(
              data = data.copy(
                info = data.info.copy(
                  openTriggerPrice = request.triggerPrice,
                  openTriggerSubmitOrder = Option(request.orderId)
                )
              )
            )
          }

          case TriggerBase.CreateFail(request, status) => {
            status match {
              case TriggerCreateFailStatus.createTimeout     => OpenErrored(data)
              case TriggerCreateFailStatus.createFireTrigger => state
            }
          }

          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[OpenTriggering])

  }
}
