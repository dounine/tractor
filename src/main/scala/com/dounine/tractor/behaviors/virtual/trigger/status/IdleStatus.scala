package com.dounine.tractor.behaviors.virtual.trigger.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.Effect
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.typed.scaladsl.ActorFlow
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.model.models.{BaseSerializer, TriggerModel}
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase._
import com.dounine.tractor.model.types.currency.{TriggerCancelFailStatus, TriggerStatus, TriggerType}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import java.time.LocalDateTime
import scala.concurrent.Await
import scala.util.{Failure, Success}

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
    val materializer: Materializer = SystemMaterializer(context.system).materializer
    val sharding: ClusterSharding = ClusterSharding(context.system)
    val config = context.system.settings.config.getConfig("app")
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
        case Run(_, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Recovery => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case e@Create(
        orderId,
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
        case e@Cancel(orderId) => {
          logger.info(command.logJson)
          state.data.triggers.get(orderId) match {
            case Some(value) =>
              value.status match {
                case TriggerStatus.submit =>
                  Effect.persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                    })
                case TriggerStatus.canceled =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, TriggerCancelFailStatus.cancelAlreadyCanceled))
                    })
                case TriggerStatus.matchs =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, TriggerCancelFailStatus.cancelAlreadyMatched))
                    })
                case TriggerStatus.error =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, TriggerCancelFailStatus.cancelAlreadyFailed))
                    })
              }
            case None => Effect.none.thenRun((_: State) => {
              e.replyTo.tell(CancelFail(orderId, TriggerCancelFailStatus.cancelOrderNotExit))
            })
          }
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
              val info = trigger._2.trigger
              info.triggerType match {
                case TriggerType.le => price >= info.triggerPrice
                case TriggerType.ge => price <= info.triggerPrice
              }
            })

          if (triggers.nonEmpty) {
            val future = Source(triggers)
              .log("entrust create")
              .mapAsync(1)(trigger => {
                val info = trigger._2.trigger
                sharding.entityRefFor(
                  EntrustBase.typeKey,
                  state.data.config.entrustId
                ).ask[BaseSerializer](ref => EntrustBase.Create(
                  orderId = trigger._1,
                  leverRate = info.leverRate,
                  offset = info.offset,
                  orderPriceType = info.orderPriceType,
                  price = info.orderPrice,
                  volume = info.volume
                )(ref))(3.seconds)
              })
              .runWith(Sink.seq)(materializer)

            val result = future.transform({
              case Failure(exception) => {
                logger.error(exception.getMessage)
                Success(Effect.none[BaseSerializer, State])
              }
              case Success(value) => {
                Success(
                  Effect.persist[BaseSerializer, State](Triggers(
                    triggers = triggers.map(trigger => {
                      (trigger._1, trigger._2.copy(
                        trigger = trigger._2.trigger,
                        status = TriggerStatus.matchs
                      ))
                    })
                  ))
                )
              }
            })(context.executionContext)
            Await.result(result, Duration.Inf)
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
                  trigger = TriggerItem(
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
          case Cancel(orderId) => {
            Idle(state.data.copy(
              triggers = state.data.triggers.map(item => {
                if (item._1 == orderId) {
                  (orderId, item._2.copy(
                    status = TriggerStatus.canceled
                  ))
                } else item
              })
            ))
          }
          case MarketTradeBehavior.SubResponse(source) => {
            source
              .throttle(1, config.getDuration("engine.trigger.speed").toMillis.milliseconds)
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
