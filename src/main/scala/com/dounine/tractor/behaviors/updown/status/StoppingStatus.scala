package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.{UpDownStatus, UpDownUpdateType}

object StoppingStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(StoppingStatus.getClass)

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
    val sendStopedStatus: (State) => Unit = (state) => {
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
        case Stop() => {
          logger.info(command.logJson)
          Effect.none
        }
        case Run(_, _, _, _, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case EntrustBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              sendStopedStatus(updateState)
            })
        }
        case EntrustBase.CancelFail(orderId, status) => {
          logger.error(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              sendStopedStatus(updateState)
            })
        }
        case TriggerBase.CancelOk(orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              sendStopedStatus(updateState)
            })
        }
        case TriggerBase.CancelFail(orderId, status) => {
          logger.error(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              sendStopedStatus(updateState)
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
          case EntrustBase.CancelOk(orderId) => {
            Stoped(
              data = data.copy(
                info = data.info.copy(
                  runLoading = false
                )
              )
            )
          }
          case EntrustBase.CancelFail(orderId, status) => {
            Stoped(
              data = data.copy(
                info = data.info.copy(
                  runLoading = false
                )
              )
            )
          }
          case TriggerBase.CancelOk(orderId) => {
            Stoped(
              data = data.copy(
                info = data.info.copy(
                  runLoading = false
                )
              )
            )
          }
          case TriggerBase.CancelFail(orderId, status) => {
            Stoped(
              data = data.copy(
                info = data.info.copy(
                  runLoading = false
                )
              )
            )
          }
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Stopping])
  }
}
