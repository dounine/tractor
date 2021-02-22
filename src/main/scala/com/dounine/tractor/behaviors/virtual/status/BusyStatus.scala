package com.dounine.tractor.behaviors.virtual.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.Effect
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.model.models.{BaseSerializer, TriggerModel}
import com.dounine.tractor.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.virtual.TriggerBase._
import com.dounine.tractor.model.types.currency.TriggerStatus

object BusyStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(BusyStatus.getClass)

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
    val sharding: ClusterSharding = ClusterSharding(context.system)
    lazy val tradeDetailBehavior: EntityRef[BaseSerializer] =
      sharding.entityRefFor(
        typeKey = MarketTradeBehavior.typeKey,
        entityId = MarketTradeBehavior.typeKey.name
      )

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
          Effect.none
        }
        case Recovery => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case RunSelfOk() => {
          logger.info(command.logJson)
          Effect.persist(command)
            .thenRun((_: State) => {
              tradeDetailBehavior.tell(
                MarketTradeBehavior.Sub(
                  symbol = state.data.symbol,
                  contractType = state.data.contractType
                )(context.self)
              )
            })
            .thenUnstashAll()

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
          case RunSelfOk() => Idle(state.data)
          case e@_ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Busy])
  }
}
