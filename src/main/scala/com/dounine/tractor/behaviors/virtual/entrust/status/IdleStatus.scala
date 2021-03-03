package com.dounine.tractor.behaviors.virtual.entrust.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.{Effect, EffectBuilder}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase._
import com.dounine.tractor.behaviors.virtual.position.PositionBase
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.{Direction, EntrustCancelFailStatus, EntrustStatus, Offset, TriggerCancelFailStatus, TriggerStatus, TriggerType}
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.typesafe.config.ConfigFactory
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
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
        direction,
        leverRate,
        offset,
        orderPriceType,
        price,
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
          state.data.entrusts.get(orderId) match {
            case Some(value) =>
              value.status match {
                case EntrustStatus.submit =>
                  Effect.persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                    })
                case EntrustStatus.matchPart =>
                  Effect.persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                    })
                case EntrustStatus.matchPartOtherCancel =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyMatchPartCancel))
                    })
                case EntrustStatus.canceled =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyCanceled))
                    })
                case EntrustStatus.matchAll =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyMatchAll))
                    })
                case EntrustStatus.error =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelFail(orderId, EntrustCancelFailStatus.cancelAlreadyFailed))
                    })
              }
            case None => Effect.none.thenRun((_: State) => {
              e.replyTo.tell(CancelFail(orderId, EntrustCancelFailStatus.cancelOrderNotExit))
            })
          }
        }
        case MarketTradeBehavior.SubResponse(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, _, price, _) => {
          logger.info(command.logJson)
          val entrusts = state.data.entrusts
            .filter(_._2.status == EntrustStatus.submit)
            .filter(item => {
              val info = item._2.entrust
              (info.offset, info.direction) match {
                case (Offset.open, Direction.buy) =>
                  price <= info.price
                case (Offset.open, Direction.sell) =>
                  price >= info.price
                case (Offset.close, Direction.sell) =>
                  price >= info.price
                case (Offset.close, Direction.buy) =>
                  price <= info.price
              }
            })
          if (entrusts.nonEmpty) {
            val future = Source(entrusts)
              .log("position create")
              .mapAsync(1)(entrust => {
                val info = entrust._2
//                sharding.entityRefFor(
//                  PositionBase.typeKey,
//                  state.data.config.positionId
//                ).ask[BaseSerializer](ref => PositionBase.Create(
//                  offset = info.entrust.offset,
//                  direction = info.entrust.direction,
//                  leverRate = info.entrust.leverRate,
//                  volume = info.entrust.volume,
//                  latestPrice = price
//                )(ref))(3.seconds)
                Future.successful(1)
              }
              )
              .runWith(Sink.seq)(materializer)

            val result: Future[EffectBuilder[BaseSerializer, State]] = future.transform({
              case Failure(exception) => Success(Effect.none[BaseSerializer, State])
              case Success(value) => Success(Effect.persist[BaseSerializer, State](Entrusts(
                entrusts = entrusts.map(entrust => {
                  (entrust._1, entrust._2.copy(
                    entrust = entrust._2.entrust,
                    status = EntrustStatus.matchAll
                  ))
                })
              )))
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
          direction,
          leverRate,
          offset,
          orderPriceType,
          price,
          volume
          ) => {
            Idle(state.data.copy(
              entrusts = state.data.entrusts ++ Map(
                orderId -> EntrustInfo(
                  entrust = EntrustItem(
                    direction = direction,
                    leverRate = leverRate,
                    offset = offset,
                    orderPriceType = orderPriceType,
                    price = price,
                    marginFrozen = 0,
                    volume = volume,
                    time = LocalDateTime.now()
                  ),
                  status = EntrustStatus.submit
                )
              )
            ))
          }
          case Cancel(orderId) => {
            Idle(state.data.copy(
              entrusts = state.data.entrusts.map(item => {
                if (item._1 == orderId) {
                  (orderId, item._2.copy(
                    status = EntrustStatus.canceled
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
          case e@Entrusts(entrusts) => {
            logger.info(e.logJson)
            Idle(state.data.copy(
              entrusts = state.data.entrusts ++ entrusts
            ))
          }
          case e@_ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Idle])
  }
}
