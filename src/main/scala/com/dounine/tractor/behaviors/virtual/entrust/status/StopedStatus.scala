package com.dounine.tractor.behaviors.virtual.entrust.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase._
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.{Logger, LoggerFactory}

object StopedStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(StopedStatus.getClass)

  def apply(
      context: ActorContext[BaseSerializer],
      shard: ActorRef[ClusterSharding.ShardCommand],
      timers: TimerScheduler[BaseSerializer]
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
    val commandHandler: (
        State,
        BaseSerializer,
        (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) => Effect[BaseSerializer, State] = (
        state: State,
        command: BaseSerializer,
        _: (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) =>
      command match {
        case Run(_, _, _, _) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((_: State) => {
              context.self.tell(RunSelfOk())
            })
        }
        case _ => {
          logger.info("stash -> {}", command.logJson)
          Effect.stash()
        }
      }

    val defaultEvent
        : (State, BaseSerializer, (State, BaseSerializer) => State) => State =
      (
          state: State,
          command: BaseSerializer,
          defaultEvent: (State, BaseSerializer) => State
      ) => {
        command match {
          case Run(marketTradeId, positionId, entrustNotifyId, contractSize) =>
            Busy(
              state.data.copy(
                contractSize = contractSize,
                config = state.data.config.copy(
                  marketTradeId = marketTradeId,
                  positionId = positionId,
                  entrustNotifyId = entrustNotifyId
                )
              )
            )
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Stoped])
  }
}
