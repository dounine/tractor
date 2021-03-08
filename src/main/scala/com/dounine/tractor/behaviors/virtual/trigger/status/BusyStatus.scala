package com.dounine.tractor.behaviors.virtual.trigger.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.Effect
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.model.models.{BaseSerializer, TriggerModel}
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase._
import com.dounine.tractor.model.types.currency.TriggerStatus

import scala.concurrent.duration._

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
    val config = context.system.settings.config.getConfig("app")
    val sharding: ClusterSharding = ClusterSharding(context.system)
    val materializer = SystemMaterializer(context.system).materializer
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
        case Run(_, _) => {
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
              Source.future(
                sharding.entityRefFor(
                  typeKey = MarketTradeBehavior.typeKey,
                  entityId = state.data.config.marketTradeId
                ).ask[BaseSerializer](
                  MarketTradeBehavior.Sub(
                    symbol = state.data.symbol,
                    contractType = state.data.contractType
                  )(_)
                )(3.seconds)
              )
                .flatMapConcat {
                  case MarketTradeBehavior.SubOk(source) => source
                }
                .throttle(1, config.getDuration("engine.trigger.speed").toMillis.milliseconds)
                .buffer(2, OverflowStrategy.dropHead)
                .runWith(
                  ActorSink.actorRef(
                    ref = context.self,
                    onCompleteMessage = StreamComplete(),
                    onFailureMessage = e => MarketTradeBehavior.SubFail(e.getMessage)
                  )
                )(materializer)
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
