package com.dounine.tractor.behaviors.virtual.trigger.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.virtual.trigger.TriggerBase._
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.OrderPriceType.OrderPriceType
import com.dounine.tractor.model.types.currency.TriggerType.TriggerType
import com.dounine.tractor.model.types.currency.{
  TriggerCancelFailStatus,
  TriggerCreateFailStatus,
  TriggerStatus,
  TriggerType
}
import com.dounine.tractor.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import scala.collection.immutable.ListMap
import scala.concurrent.duration._

object IdleStatus extends JsonParse {

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
    val materializer: Materializer = SystemMaterializer(
      context.system
    ).materializer
    val sharding: ClusterSharding = ClusterSharding(context.system)
    val config = context.system.settings.config.getConfig("app")
    val historySize = config.getInt("engine.trigger.historySize")
    val maxSize = config.getInt("engine.trigger.maxSize")
    val cropTriggers: (Map[String, TriggerInfo]) => Map[String, TriggerInfo] =
      (triggers) => {
        if (triggers.size > maxSize) {
          ListMap(
            triggers.toSeq
              .sortWith(_._2.trigger.time isAfter _._2.trigger.time): _*
          ).take(maxSize)
        } else if (
          triggers.values
            .count(_.status != TriggerStatus.submit) > historySize
        ) {
          triggers
            .filter(_._2.status == TriggerStatus.submit) ++
            ListMap(
              triggers
                .filter(_._2.status != TriggerStatus.submit)
                .toSeq
                .sortWith(_._2.trigger.time isAfter _._2.trigger.time): _*
            ).take(maxSize)
        } else triggers
      }
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
          Effect.none
        }
        case Recovery => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case UpdateLeverRate(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case e @ IsCanChangeLeverRate() => {
          logger.info(command.logJson)
          Effect.none.thenRun((state: State) => {
            if (
              state.data.triggers.values
                .exists(t => t.status == TriggerStatus.submit)
            ) {
              e.replyTo.tell(ChangeLeverRateNo())
            } else {
              e.replyTo.tell(ChangeLeverRateYes())
            }
          })
        }

        case e @ Create(
              orderId: String,
              offset: Offset,
              orderPriceType: OrderPriceType,
              triggerType: TriggerType,
              orderPrice: Double,
              triggerPrice: Double,
              volume: Int
            ) => {
          logger.info(command.logJson)
          if (
            state.data.triggers.count(
              _._2.status == TriggerStatus.submit
            ) > maxSize
          ) {
            Effect.none.thenRun((_: State) => {
              e.replyTo.tell(
                CreateFail(
                  e,
                  TriggerCreateFailStatus.createSizeOverflow
                )
              )
            })
          } else {
            state.data.price match {
              case Some(price) => {
                val fireTrigger = triggerType match {
                  case TriggerType.le => {
                    price <= triggerPrice
                  }
                  case TriggerType.ge => price >= triggerPrice
                }
                if (fireTrigger) {
                  Effect.none.thenRun((_: State) => {
                    e.replyTo.tell(
                      CreateFail(e, TriggerCreateFailStatus.createFireTrigger)
                    )
                  })
                } else {
                  Effect
                    .persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CreateOk(e))
                    })
                }
              }
              case None => {
                Effect
                  .persist(command)
                  .thenRun((_: State) => {
                    e.replyTo.tell(CreateOk(e))
                  })
              }
            }
          }
        }
        case e @ Cancel(orderId) => {
          logger.info(command.logJson)
          state.data.triggers.get(orderId) match {
            case Some(value) =>
              value.status match {
                case TriggerStatus.submit =>
                  Effect
                    .persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                    })
                case TriggerStatus.canceled =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          TriggerCancelFailStatus.cancelAlreadyCanceled
                        )
                      )
                    })
                case TriggerStatus.matchs =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          TriggerCancelFailStatus.cancelAlreadyMatched
                        )
                      )
                    })
                case TriggerStatus.error =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          TriggerCancelFailStatus.cancelAlreadyFailed
                        )
                      )
                    })
              }
            case None =>
              Effect.none.thenRun((_: State) => {
                e.replyTo.tell(
                  CancelFail(
                    orderId,
                    TriggerCancelFailStatus.cancelOrderNotExit
                  )
                )
              })
          }
        }
        case Trigger(price) => {
          logger.info(command.logJson)
          val triggers = state.data.triggers
            .filter(_._2.status == TriggerStatus.submit)
            .filter(trigger => {
              val info = trigger._2.trigger
              info.triggerType match {
                case TriggerType.le => price <= info.triggerPrice
                case TriggerType.ge => price >= info.triggerPrice
              }
            })

          if (triggers.nonEmpty) {
            Source(triggers)
              .log("entrust create")
              .runForeach(trigger => {
                val info = trigger._2.trigger
                Source
                  .future(
                    sharding
                      .entityRefFor(
                        EntrustBase.typeKey,
                        state.data.config.entrustId
                      )
                      .ask[BaseSerializer](
                        EntrustBase.Create(
                          orderId = trigger._1,
                          offset = info.offset,
                          orderPriceType = info.orderPriceType,
                          price = price,
                          volume = info.volume
                        )(_)
                      )(3.seconds)
                  )
                  .map {
                    case EntrustBase.CreateOk(r) => TriggerOk(trigger)
                  }
                  .runWith(
                    ActorSink.actorRef(
                      ref = context.self,
                      onCompleteMessage = StreamComplete(),
                      onFailureMessage = e => TriggerFail(trigger)
                    )
                  )(materializer)
              })(materializer)
          } else {
            Effect.none
          }
          Effect.none
        }
        case TriggerOk(request) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case TriggerFail(request) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, price, _, _) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              context.self.tell(Trigger(price))
            })
        }
        case EntrustBase.CreateOk(request) => {
          logger.info(command.logJson)
          Effect.none
        }
        case EntrustBase.CreateFail(request, status) => {
          logger.error(command.logJson)
          Effect.none
        }
        case StreamComplete() => {
          Effect.none
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
          case UpdateLeverRate(value) => {
            Idle(
              state.data.copy(
                leverRate = value
              )
            )
          }
          case Create(
                orderId,
                offset,
                orderPriceType,
                triggerType,
                orderPrice,
                triggerPrice,
                volume
              ) => {
            Idle(
              state.data.copy(
                triggers = state.data.triggers ++ Map(
                  orderId -> TriggerInfo(
                    trigger = TriggerItem(
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
              )
            )
          }
          case Cancel(orderId) => {
            Idle(
              state.data.copy(
                triggers = state.data.triggers.map(item => {
                  if (item._1 == orderId) {
                    (
                      orderId,
                      item._2.copy(
                        status = TriggerStatus.canceled
                      )
                    )
                  } else item
                })
              )
            )
          }
          case MarketTradeBehavior.TradeDetail(_, _, _, price, _, _) => {
            Idle(
              state.data.copy(
                price = Option(price)
              )
            )
          }
          case TriggerOk(info) => {
            Idle(
              state.data.copy(
                triggers = cropTriggers(state.data.triggers) ++ Map(
                  info.copy(
                    _2 = info._2.copy(
                      status = TriggerStatus.matchs
                    )
                  )
                )
              )
            )
          }
          case TriggerFail(info) => {
            Idle(
              state.data.copy(
                triggers = cropTriggers(state.data.triggers) ++ Map(
                  info.copy(
                    _2 = info._2.copy(
                      status = TriggerStatus.error
                    )
                  )
                )
              )
            )
          }
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Idle])
  }
}
