package com.dounine.tractor.behaviors.virtual.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.Effect
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorSource
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.model.models.{BaseSerializer, TriggerModel}
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.virtual.TriggerBase._
import com.dounine.tractor.model.types.currency.{TriggerStatus, TriggerType}

import scala.concurrent.duration._
import java.time.LocalDateTime

object IdleStatus extends ActorSerializerSuport {

  private final val logger: Logger =
    LoggerFactory.getLogger(IdleStatus.getClass)

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
        case e@Create(
        orderId,
        direction,
        leverRate,
        offset,
        orderPriceType,
        triggerType,
        orderPrice,
        triggerPrice,
        volume
        ) => {
          logger.info(command.logJson)
          Effect.persist(command)
            .thenRun((_: State) => {
              e.replyTo.tell(CreateOk(orderId))
            })
        }
        case MarketTradeBehavior.SubResponse(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, _, price, _) => {
          logger.info(command.logJson)
          val triggers = state.data.triggers
            .filter(_._2.status == TriggerStatus.submit)
            .filter(trigger => {
              val info = trigger._2.info
              info.triggerType match {
                case TriggerType.le => price >= info.triggerPrice
                case TriggerType.ge => price <= info.triggerPrice
              }
            })

          if (triggers.nonEmpty) {
            Effect.persist(Triggers(
              triggers = triggers.map(trigger => {
                (trigger._1, trigger._2.copy(
                  info = trigger._2.info,
                  status = TriggerStatus.matchs
                ))
              })
            ))
          } else {
            Effect.none
          }
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
          case Create(
          orderId,
          direction,
          leverRate,
          offset,
          orderPriceType,
          triggerType,
          orderPrice,
          triggerPrice,
          volume) => {
            Idle(state.data.copy(
              triggers = state.data.triggers ++ Map(
                orderId -> TriggerInfo(
                  info = TriggerItem(
                    orderId = orderId,
                    direction = direction,
                    leverRate = leverRate,
                    offset = offset,
                    orderPriceType = orderPriceType,
                    triggerType = triggerType,
                    orderPrice = orderPrice,
                    triggerPrice = triggerPrice,
                    volume = volume,
                    time = LocalDateTime.now()
                  ),
                  status = TriggerStatus.submit
                )
              )
            ))
          }
          case MarketTradeBehavior.SubResponse(source) => {
            val materializer: Materializer = SystemMaterializer(context.system).materializer
            source
              .throttle(1, 200.milliseconds)
              .buffer(1, OverflowStrategy.dropHead)
              .runWith(Sink.foreach(context.self.tell))(materializer)
            state
          }
          case e@Triggers(triggers) => {
            logger.info(e.logJson)
            Idle(state.data.copy(
              triggers = state.data.triggers ++ triggers
            ))
          }
          case e@_ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Idle])
  }
}
