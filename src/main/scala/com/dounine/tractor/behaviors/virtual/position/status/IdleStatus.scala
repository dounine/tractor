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
        case ReplaceData(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case UpdateLeverRate(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case e@IsCanChangeLeverRate() => {
          logger.info(command.logJson)
          Effect.none.thenRun((state: State) => {
            if (state.data.position.isDefined) {
              e.replyTo.tell(ChangeLeverRateNo())
            } else {
              e.replyTo.tell(ChangeLeverRateYes())
            }
          })
        }

        case e@Create(
        offset: Offset,
        volume: Int,
        latestPrice: Double
        ) => {
          logger.info(command.logJson)
          val takeRate = 0.0004
          offset match {
            case Offset.open => {
              state.data.position match {
                case Some(position) => {
                  val fee = volume * state.data.contractSize / latestPrice * takeRate

                  val costHold: Double =
                    state.data.contractSize * (position.volume + volume) / (position.volume * state.data.contractSize / position.costHold + volume * state.data.contractSize / latestPrice)
                  val mergePosition =
                    position.copy(
                      costHold = costHold,
                      volume = position.volume + volume,
                      openFee = position.openFee + fee,
                      positionMargin =
                        state.data.contractSize * (position.volume + volume) / costHold / state.data.leverRate.toString.toInt
                    )

                  Effect
                    .persist(MergePosition(mergePosition))
                    .thenRun((_: State) => {
                      logger.info(MergeOk().logJson)
                      e.replyTo.tell(MergeOk())
                    })

                }
                case None => {
                  val marginFrozen = state.data.contractSize * volume / latestPrice / state.data.leverRate.toString.toInt
                  val fee = volume * state.data.contractSize / latestPrice * takeRate
                  Effect.persist(NewPosition(
                    PositionInfo(
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
                      logger.info(OpenOk().logJson)
                      e.replyTo.tell(
                        OpenOk()
                      )
                    })
                }
              }
            }
            case Offset.close => {
              state.data.position match {
                case Some(position) => {
                  val costHold = state.data.contractSize * (position.volume + volume) / (position.volume * state.data.contractSize / position.costHold + volume * state.data.contractSize / latestPrice)
                  val closeFee = volume * state.data.contractSize / costHold * takeRate
                  val balanceService = ServiceSingleton.get(classOf[BalanceRepository])
                  if (position.volume == volume) {
                    val result = Source.future(
                      balanceService.mergeBalance(
                        phone = state.data.phone,
                        symbol = state.data.symbol,
                        balance = (state.data.direction match {
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
                          .persist(RemovePosition())
                          .thenRun((_: State) => {
                            logger.info(CloseOk().logJson)
                            e.replyTo.tell(CloseOk())
                          })
                      })
                      .recover(ee => {
                        logger.error(ee.getMessage)
                        Effect.none
                          .thenRun((_: State) => {
                            logger.info(CreateFail(PositionCreateFailStatus.createSystemError).logJson)
                            e.replyTo.tell(CreateFail(PositionCreateFailStatus.createSystemError))
                          })
                      })
                      .runWith(Sink.head)(materializer)
                    Await.result(result, Duration.Inf)
                  } else if (volume < position.volume) {
                    val sumVolume = position.volume - volume
                    val sumCloseFee = position.closeFee + closeFee
                    val sumPositionMargin =
                      state.data.contractSize * (position.volume - volume) / position.costHold / state.data.leverRate.toString.toInt

                    val result = Source.future(
                      balanceService.mergeBalance(
                        phone = state.data.phone,
                        symbol = state.data.symbol,
                        balance = (state.data.direction match {
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
                            position = position.copy(
                              volume = sumVolume,
                              closeFee = sumCloseFee,
                              positionMargin = sumPositionMargin
                            )
                          ))
                          .thenRun((_: State) => {
                            logger.info(CloseOk().logJson)
                            e.replyTo.tell(CloseOk())
                          })
                      })
                      .recover(ee => {
                        logger.error(ee.getMessage)
                        Effect.none
                          .thenRun((_: State) => {
                            logger.info(CreateFail(PositionCreateFailStatus.createSystemError).logJson)
                            e.replyTo.tell(CreateFail(PositionCreateFailStatus.createSystemError))
                          })
                      })
                      .runWith(Sink.head)(materializer)
                    Await.result(result, Duration.Inf)
                  } else {
                    Effect
                      .none
                      .thenRun((_: State) => {
                        logger.info(CreateFail(
                          PositionCreateFailStatus.createCloseNotEnoughIsAvailable
                        ).logJson)
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
                    logger.info(CreateFail(
                      PositionCreateFailStatus.createClosePositionNotExit
                    ).logJson)
                    e.replyTo.tell(CreateFail(
                      PositionCreateFailStatus.createClosePositionNotExit
                    ))
                  })
                }
              }

            }
          }
        }
        case MarketTradeBehavior.SubOk(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, price, _, _) => {
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
          case UpdateLeverRate(value) => {
            Idle(state.data.copy(
              leverRate = value
            ))
          }
          case ReplaceData(data) => {
            Idle(data)
          }
          case MergePosition(position) => {
            Idle(
              state.data.copy(
                position = Option(position)
              )
            )
          }
          case NewPosition(position) => {
            Idle(
              state.data.copy(
                position = Option(position)
              )
            )
          }
          case RemovePosition() => {
            Idle(
              state.data.copy(
                position = Option.empty
              )
            )
          }
          case MarketTradeBehavior.SubOk(source) => {
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
