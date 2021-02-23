package com.dounine.tractor.behaviors.virtual.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.virtual.TriggerBase._

object StopedStatus extends JsonParse {

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
        case Run => {
          logger.info(command.logJson)
          Effect.persist(command)
            .thenRun((_: State) => {
              context.self.tell(RunSelfOk())
            })
          //            .thenReply(context.self)(_ => RunSelfOk())
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
          case Run => Busy(state.data)
          case e@_ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Stoped])
  }
}
