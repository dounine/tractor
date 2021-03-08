package com.dounine.tractor.behaviors.updown.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.Effect
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.updown.UpDownBase._
import com.dounine.tractor.behaviors.updown.UpDownBehavior.ShareData
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

object StopedStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(StopedStatus.getClass)

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
    val commandHandler: (
      State,
        BaseSerializer,
        (State, BaseSerializer) => Effect[BaseSerializer, State]
      ) => Effect[BaseSerializer, State] = (
                                             state: State,
                                             command: BaseSerializer,
                                             defaultCommand: (State, BaseSerializer) => Effect[BaseSerializer, State]
                                           ) => {
      implicit val ec: ExecutionContextExecutor = context.executionContext
      val data: DataStore = state.data
      command match {
        case Stop() => {
          logger.info(command.logJson)
          Effect.none
        }
        case Run(
        _,
        _,
        _,
        _
        ) => {
          logger.info(command.logJson)
          Effect.persist(command)
            .thenRun((updateState: State) => {
              Source
                .future(
                  sharding.entityRefFor(
                    MarketTradeBehavior.typeKey,
                    updateState.data.config.marketTradeId
                  ).ask[BaseSerializer](MarketTradeBehavior.Sub(
                    symbol = updateState.data.symbol,
                    contractType = updateState.data.contractType
                  )(_))(3.seconds)
                )
                .flatMapConcat {
                  case MarketTradeBehavior.SubOk(source) => {
                    source
                  }
                }
                .buffer(2, OverflowStrategy.dropHead)
                .throttle(1, 100.milliseconds)
                .runWith(ActorSink.actorRef(context.self, StreamComplete(), e => MarketTradeBehavior.SubFail(e.getMessage)))(materializer)

              Source.future(
                sharding.entityRefFor(
                  EntrustNotifyBehavior.typeKey,
                  updateState.data.config.entrustNotifyId
                ).ask[BaseSerializer](EntrustNotifyBehavior.Sub(
                  symbol = updateState.data.symbol,
                  contractType = updateState.data.contractType,
                  direction = updateState.data.direction
                )(_))(3.seconds)
              )
                .flatMapConcat {
                  case EntrustNotifyBehavior.SubOk(source) => source
                }
                .runWith(ActorSink.actorRef(context.self, StreamComplete(), e => EntrustNotifyBehavior.SubFail(e.getMessage)))(materializer)

              context.self.tell(Trigger())
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
        command match {
          case Run(marketTradeId, entrustId, triggerId, entrustNotifyId) => {
            OpenTriggering(
              data = state.data.copy(
                config = state.data.config.copy(
                  marketTradeId = marketTradeId,
                  entrustId = entrustId,
                  triggerId = triggerId,
                  entrustNotifyId = entrustNotifyId
                )
              )
            )
          }

          case _ => defaultEvent(state, command)
        }
      }

    (commandHandler, defaultEvent, classOf[Stoped])
  }
}
