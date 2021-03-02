package com.dounine.tractor.behaviors.virtual.position.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.behaviors.virtual.position.PositionBase._
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.LeverRate.LeverRate
import com.dounine.tractor.model.types.currency.Offset.Offset
import com.dounine.tractor.model.types.currency.{Direction, EntrustCancelFailStatus, EntrustStatus, Offset, PositionCreateFailStatus}
import com.dounine.tractor.service.virtual.BalanceRepository
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.dounine.tractor.tools.util.ServiceSingleton
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import scala.concurrent.Await
import scala.concurrent.duration._

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
        case Run(_) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Recovery => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case e@Create(
        offset: Offset,
        direction: Direction,
        leverRate: LeverRate,
        volume: Int,
        latestPrice: Double
        ) => {
          logger.info(command.logJson)
          val takeRate = 0.0004
          offset match {
            case Offset.open => {
              state.data.positions.find(_._1 == (offset, direction)) match {
                case Some(tp2) => {
                  val position = tp2._2
                  val fee = volume * state.data.contractSize / latestPrice * takeRate

                  val costHold: Double =
                    state.data.contractSize * (position.volume + volume) / (position.volume * state.data.contractSize / position.costHold + volume * state.data.contractSize / latestPrice)
                  val mergePosition =
                    position.copy(
                      costHold = costHold,
                      volume = position.volume + volume,
                      openFee = position.openFee + fee,
                      positionMargin =
                        state.data.contractSize * (position.volume + volume) / costHold / position.leverRate.toString.toInt
                    )

                  Effect
                    .persist(MergePosition(offset, direction, mergePosition))
                    .thenRun((_: State) => {
                      e.replyTo.tell(MergeOk())
                    })

                }
                case None => {
                  val marginFrozen = state.data.contractSize * volume / latestPrice / leverRate.toString.toInt
                  val fee = volume * state.data.contractSize / latestPrice * takeRate
                  Effect.persist(NewPosition(
                    offset,
                    direction,
                    PositionInfo(
                      leverRate = leverRate,
                      volume = volume,
                      available = volume,
                      frozen = 0,
                      openFee = fee,
                      closeFee = 0,
                      costOpen = latestPrice,
                      costHold = latestPrice,
                      profitUnreal = 0,
                      profitRate = 0,
                      profit = 0,
                      positionMargin = marginFrozen,
                      createTime = LocalDateTime.now()
                    )
                  ))
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        OpenOk()
                      )
                    })
                }
              }
            }
            case Offset.close => {
              state.data.positions.find(_._1 == (offset, Direction.reverse(direction))) match {
                case Some(tp2) => {
                  val position = tp2._2
                  val costHold = state.data.contractSize * (position.volume + volume) / (position.volume * state.data.contractSize / position.costHold + volume * state.data.contractSize / latestPrice)
                  val closeFee = volume * state.data.contractSize / costHold * takeRate
                  val balanceService = ServiceSingleton.get(classOf[BalanceRepository])
                  if (position.volume == volume) {
                    val result = Source.future(
                      balanceService.mergeBalance(
                        phone = state.data.phone,
                        symbol = state.data.symbol,
                        balance = (tp2._1._2 match {
                          case Direction.buy =>
                            (state.data.contractSize * position.volume / position.costHold) - (state.data.contractSize * position.volume / costHold)
                          case Direction.sell =>
                            (state.data.contractSize * position.volume / costHold) - (state.data.contractSize * position.volume / position.costHold)
                        }) - position.openFee + position.closeFee + closeFee
                      )
                    )
                      .idleTimeout(3.seconds)
                      .log("update balance")
                      .map(item => {
                        Effect
                          .persist(RemovePosition(
                            offset = offset,
                            direction = tp2._1._2
                          ))
                          .thenRun((_: State) => {
                            e.replyTo.tell(CloseOk())
                          })
                      })
                      .recover(ee => {
                        logger.error(ee.getMessage)
                        Effect.none
                          .thenRun((_: State) => {
                            e.replyTo.tell(CreateFail(PositionCreateFailStatus.createSystemError))
                          })
                      })
                      .runWith(Sink.head)(materializer)
                    Await.result(result, Duration.Inf)
                  } else if (volume < position.volume) {
                    val sumVolume = position.volume - volume
                    val sumCloseFee = position.closeFee + closeFee
                    val sumPositionMargin =
                      state.data.contractSize * (position.volume - volume) / position.costHold / position.leverRate.toString.toInt

                    val result = Source.future(
                      balanceService.mergeBalance(
                        phone = state.data.phone,
                        symbol = state.data.symbol,
                        balance = (tp2._1._2 match {
                          case Direction.buy =>
                            (state.data.contractSize * volume / position.costHold) - (state.data.contractSize * volume / costHold)
                          case Direction.sell =>
                            (state.data.contractSize * volume / costHold) - (state.data.contractSize * volume / position.costHold)
                        })
                      )
                    )
                      .idleTimeout(3.seconds)
                      .log("update balance")
                      .map(item => {
                        Effect
                          .persist(MergePosition(
                            offset = offset,
                            direction = tp2._1._2,
                            position = position.copy(
                              volume = sumVolume,
                              closeFee = sumCloseFee,
                              positionMargin = sumPositionMargin
                            )
                          ))
                          .thenRun((_: State) => {
                            e.replyTo.tell(CloseOk())
                          })
                      })
                      .recover(ee => {
                        logger.error(ee.getMessage)
                        Effect.none
                          .thenRun((_: State) => {
                            e.replyTo.tell(CreateFail(PositionCreateFailStatus.createSystemError))
                          })
                      })
                      .runWith(Sink.head)(materializer)
                    Await.result(result, Duration.Inf)
                  } else {
                    Effect
                      .none
                      .thenRun((_: State) => {
                        e.replyTo.tell(
                          CreateFail(
                            PositionCreateFailStatus.createCloseNotEnoughIsAvailable
                          )
                        )
                      })
                  }
                }
                case None => {
                  Effect.none.thenRun((_: State) => {
                    e.replyTo.tell(CreateFail(
                      PositionCreateFailStatus.createClosePositionNotExit
                    ))
                  })
                }
              }

            }
          }
        }
        case MarketTradeBehavior.SubResponse(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, _, price, _) => {
          logger.info(command.logJson)
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
          case MergePosition(offset, direction, position) => {
            Idle(
              state.data.copy(
                positions = state.data.positions ++ Map(
                  (offset, direction) -> position
                )
              )
            )
          }
          case NewPosition(offset, direction, position) => {
            Idle(
              state.data.copy(
                positions = state.data.positions ++ Map(
                  (offset, direction) -> position
                )
              )
            )
          }
          case RemovePosition(offset, direction) => {
            Idle(
              state.data.copy(
                positions = state.data.positions.filterNot(_._1 == (offset, direction))
              )
            )
          }
          case MarketTradeBehavior.SubResponse(source) => {
            source
              .throttle(1, config.getDuration("engine.position.speed").toMillis.milliseconds)
              .buffer(1, OverflowStrategy.dropHead)
              .runWith(Sink.foreach(context.self.tell))(materializer)
            state
          }
          case e@_ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Idle])
  }
}
