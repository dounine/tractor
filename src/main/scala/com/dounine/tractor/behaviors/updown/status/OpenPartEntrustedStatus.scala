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
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency.{EntrustCancelFailStatus, EntrustStatus, Offset, UpDownStatus, UpDownUpdateType}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OpenPartEntrustedStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(OpenPartEntrustedStatus.getClass)

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
                                           ) =>
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
              timers.cancel(entrustTimeoutName)
              updateState.data.info.openEntrustSubmitOrder match {
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
                      sharding.entityRefFor(
                        EntrustBase.typeKey,
                        state.data.config.entrustId
                      ).ask(
                        EntrustBase.Cancel(orderId)(_)
                      )(3.seconds)
                    )
                    .runWith(ActorSink.actorRef(
                      ref = context.self,
                      onCompleteMessage = StreamComplete(),
                      onFailureMessage = e => EntrustBase.CancelFail(orderId, EntrustCancelFailStatus.cancelTimeout)
                    ))(materializer)
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

        case EntrustNotifyBehavior.Push(notif) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              (notif.direction, notif.offset) match {
                case (data.direction, Offset.open) =>
                  notif.entrustStatus match {
                    case EntrustStatus.canceled => //ignore
                    case EntrustStatus.matchAll => {
                      timers.cancel(triggerName)
                      timers.cancel(entrustTimeoutName)
                      pushStatus(shareData, UpDownStatus.Opened)
                    }
                    case EntrustStatus.matchPart => {
                      timers.cancel(triggerName)
                      timers.startSingleTimer(
                        key = entrustTimeoutName,
                        msg = EntrustTimeout(
                          status = notif.entrustStatus,
                          orderId = notif.orderId
                        ),
                        delay = data.info.openEntrustTimeout
                      )
                    }
                    case EntrustStatus.matchPartOtherCancel => {
                      timers.cancel(triggerName)
                      timers.cancel(entrustTimeoutName)
                      pushStatus(shareData, UpDownStatus.Opened)
                    }
                  }
                case _ => //ignore
              }
            })
        }

        case EntrustBase.CancelOk(result) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              timers.cancel(entrustTimeoutName)
              pushStatus(shareData, UpDownStatus.Opened)
            })
        }
        case EntrustBase.CancelFail(orderId, status) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              status match {
                case EntrustCancelFailStatus.cancelOrderNotExit => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
                case EntrustCancelFailStatus.cancelAlreadyCanceled => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
                case EntrustCancelFailStatus.cancelAlreadyMatchAll => {
                  pushStatus(shareData, UpDownStatus.Opened)
                }
                case EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
                case EntrustCancelFailStatus.cancelAlreadyFailed => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
                case EntrustCancelFailStatus.cancelTimeout => {
                  pushStatus(shareData, UpDownStatus.OpenErrored)
                }
              }
            })
        }
        case EntrustTimeout(orderStatus, orderId) => {
          logger.info(command.logJson)
          Effect
            .none
            .thenRun((updateState: State) => {
              val data: DataStore = updateState.data
              orderStatus match {
                case EntrustStatus.matchPart => {
                  Source
                    .future(
                      sharding.entityRefFor(
                        EntrustBase.typeKey,
                        updateState.data.config.entrustId
                      ).ask[BaseSerializer](
                        EntrustBase.Cancel(orderId)(_)
                      )(3.seconds)
                    )
                    .runWith(ActorSink.actorRef(
                      ref = context.self,
                      onCompleteMessage = StreamComplete(),
                      onFailureMessage = e => EntrustBase.CancelFail(orderId, EntrustCancelFailStatus.cancelTimeout)
                    ))(materializer)
                }
              }
            })
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
            data.info.openEntrustSubmitOrder match {
              case Some(orderId) =>
                Stopping(data =
                  data.copy(
                    info = data.info.copy(
                      openEntrustSubmitOrder = Option.empty,
                      runLoading = true
                    )
                  )
                )
              case None =>
                Stoped(data)
            }
          }

          case EntrustNotifyBehavior.Push(notif) => {
            (notif.direction, notif.offset) match {
              case (data.direction, Offset.open) =>
                notif.entrustStatus match {
                  case EntrustStatus.canceled => state
                  case EntrustStatus.matchAll =>
                    Opened(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
                          closeVolume = data.info.closeVolume + notif.volume,
                          openFee = data.info.openFee + notif.fee
                        )
                      )
                    )
                  case EntrustStatus.matchPart =>
                    OpenPartEntrusted(
                      data = data.copy(
                        info = data.info.copy(
                          openTriggerSubmitOrder = Option.empty,
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
                          openFee = data.info.openFee + notif.fee,
                          closeVolume = data.info.closeVolume + notif.volume
                        )
                      )
                    )
                }
              case _ => state
            }
          }

          case EntrustBase.CancelOk(orderId) => {
            Opened(data)
          }

          case EntrustBase.CancelFail(orderId, status) => {
            status match {
              case EntrustCancelFailStatus.cancelOrderNotExit =>{
                OpenErrored(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyCanceled => {
                OpenErrored(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyMatchAll => {
                Opened(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyMatchPartCancel => {
                OpenErrored(data)
              }
              case EntrustCancelFailStatus.cancelAlreadyFailed => {
                OpenErrored(data)
              }
              case EntrustCancelFailStatus.cancelTimeout => {
                OpenErrored(data)
              }
            }
          }

          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[OpenPartEntrusted])
  }
}
