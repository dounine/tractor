package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.UpDownStatus.UpDownStatus
import com.dounine.tractor.model.types.currency.{UpDownStatus, UpDownUpdateType}
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}

object OpenedStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(OpenedStatus.getClass)

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
    val cancelTrigger: () => Unit = () => {
      timers.cancel(key = triggerName)
    }
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
              cancelTrigger()
              pushInfos(
                data = shareData,
                infos = Map(
                  UpDownUpdateType.status -> UpDownStatus.Stoped,
                  UpDownUpdateType.run -> false,
                  UpDownUpdateType.runLoading -> false
                ),
                context = context
              )
            })
        }
        case EntrustNotifyBehavior.Receive(notif) => {
          logger.info(command.logJson)
          Effect.none
        }
        case EntrustTimeout(status, orderId) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Trigger(handlePrice) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              context.self.tell(Trigger())
              pushStatus(shareData, UpDownStatus.CloseTriggering)
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
            Stoped(data =
              data.copy(
                info = data.info.copy(
                  runLoading = false
                )
              )
            )
          }

          case Trigger(handlePrice) => {
            CloseTriggering(data)
          }
          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[Opened])
  }
}
