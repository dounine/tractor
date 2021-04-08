package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._

object CloseEntrustedStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(CloseEntrustedStatus.getClass)

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
        data = shareData,
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
              data.info.closeEntrustSubmitOrder match {
                case Some(orderId) => {
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
                          EntrustBase.typeKey,
                          updateState.data.config.entrustId
                        )
                        .ask[BaseSerializer](
                          EntrustBase.Cancel(orderId)(_)
                        )(3.seconds)
                    )
                    .runWith(
                      ActorSink.actorRef(
                        ref = context.self,
                        onCompleteMessage = StreamComplete(),
                        onFailureMessage = e => {
                          logger.error(e.logJson)
                          EntrustBase.CancelFail(
                            orderId,
                            EntrustCancelFailStatus.cancelTimeout
                          )
                        }
                      )
                    )(materializer)
                }
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
        case Trigger(_) => {
          logger.info(command.logJson)
          Effect.none
        }
        case EntrustNotifyBehavior.Receive(notif) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              val reverDirection: Direction =
                Direction.reverse(data.direction)
              (notif.direction, notif.offset) match {
                case (`reverDirection`, Offset.close) =>
                  notif.entrustStatus match {
                    case EntrustStatus.submit | EntrustStatus.canceled => //ignore
                    case EntrustStatus.matchAll => {
                      timers.cancel(triggerName)
                      timers.cancel(entrustTimeoutName)
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
                      pushInfos(
                        data = shareData,
                        infos = Map(
                          UpDownUpdateType.status -> UpDownStatus.ClosePartMatched
                        ),
                        context = context
                      )
                    }
                    case EntrustStatus.matchPartOtherCancel => {
                      timers.cancel(triggerName)
                      pushStatus(shareData, UpDownStatus.Closed)
                    }
                  }
                case _ => state
              }
            })
        }
        case EntrustTimeout(orderStatus, orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              orderStatus match {
                case EntrustStatus.canceled => //ignore
                case EntrustStatus.submit => {
                  Source
                    .future(
                      sharding
                        .entityRefFor(
                          EntrustBase.typeKey,
                          updateState.data.config.entrustId
                        )
                        .ask[BaseSerializer](
                          EntrustBase.Cancel(orderId)(_)
                        )(3.seconds)
                    )
                    .runWith(
                      ActorSink.actorRef(
                        ref = context.self,
                        onCompleteMessage = StreamComplete(),
                        onFailureMessage = e =>
                          EntrustBase.CancelFail(
                            orderId,
                            EntrustCancelFailStatus.cancelTimeout
                          )
                      )
                    )(materializer)
                }
              }
            })
        }
        case EntrustBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              context.self.tell(Trigger())
            })
        }
        case EntrustBase.CancelFail(orderId, status) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              status match {
                case EntrustCancelFailStatus.cancelOrderNotExit |
                    EntrustCancelFailStatus.cancelAlreadyCanceled |
                    EntrustCancelFailStatus.cancelAlreadyFailed |
                    EntrustCancelFailStatus.cancelTimeout => {
                  pushStatus(shareData, UpDownStatus.CloseErrored)
                }
                case EntrustCancelFailStatus.cancelAlreadyMatchAll => {
                  pushStatus(shareData, UpDownStatus.Closed)
                }
                case EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {
                  pushStatus(shareData, UpDownStatus.Closed)
                }
              }
            })
        }
        case TriggerBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect.none
        }
        case TriggerBase.CancelFail(orderId, status) => {
          logger.error(command.logJson)
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
            data.info.closeEntrustSubmitOrder match {
              case Some(_) =>
                Stopping(
                  data = data.copy(
                    info = data.info.copy(
                      closeEntrustSubmitOrder = Option.empty,
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
              Direction.reverse(data.direction)
            (notif.direction, notif.offset) match {
              case (`reverDirection`, Offset.close) =>
                notif.entrustStatus match {
                  case EntrustStatus.submit | EntrustStatus.canceled =>
                    state
                  case EntrustStatus.matchAll => {
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
                  case EntrustStatus.matchPart => {
                    ClosePartMatched(
                      data = data.copy(
                        info = data.info.copy(
                          closeTriggerSubmitOrder = Option.empty,
                          closeFee = data.info.closeFee + notif.fee
                        )
                      )
                    )
                  }
                  case EntrustStatus.matchPartOtherCancel => {
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
                }
              case _ => state
            }
          }

          case EntrustTimeout(status, orderId) => {
            status match {
              case EntrustStatus.submit => {
                CloseTriggering(
                  data
                )
              }
              case EntrustStatus.matchPart => {
                Opened(data)
              }
            }
          }

          case EntrustBase.CancelOk(orderId) => {
            CloseTriggering(data)
          }

          case EntrustBase.CancelFail(orderId, status) => {
            status match {
              case EntrustCancelFailStatus.cancelOrderNotExit |
                  EntrustCancelFailStatus.cancelAlreadyCanceled |
                  EntrustCancelFailStatus.cancelAlreadyFailed |
                  EntrustCancelFailStatus.cancelTimeout => {
                CloseErrored(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyMatchAll => {
                Closed(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {
                Closed(data)
              }
            }
          }

          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[CloseEntrusted])
  }
}
