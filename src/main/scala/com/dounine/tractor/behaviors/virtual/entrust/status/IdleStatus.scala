package com.dounine.tractor.behaviors.virtual.entrust.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.persistence.typed.scaladsl.{Effect, EffectBuilder}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{Materializer, OverflowStrategy, SystemMaterializer}
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.EntrustBase._
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.PositionBase
import com.dounine.tractor.model.models.{BaseSerializer, NotifyModel}
import com.dounine.tractor.model.types.currency.{
  AggregationActor,
  Direction,
  EntrustCancelFailStatus,
  EntrustCreateFailStatus,
  EntrustStatus,
  Offset,
  OrderPriceType,
  OrderType,
  Role
}
import com.dounine.tractor.service.virtual.BalanceRepository
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.dounine.tractor.tools.util.ServiceSingleton
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import scala.collection.immutable.ListMap
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
    val materializer: Materializer = SystemMaterializer(
      context.system
    ).materializer
    val sharding: ClusterSharding = ClusterSharding(context.system)
    val config = context.system.settings.config.getConfig("app")
    val historySize = config.getInt("engine.entrust.historySize")
    val maxSize = config.getInt("engine.entrust.maxSize")
    val cropEntrusts: (Map[String, EntrustInfo]) => Map[String, EntrustInfo] =
      (entrusts) => {
        if (entrusts.size > maxSize) {
          ListMap(
            entrusts.toSeq
              .sortWith(_._2.entrust.time isAfter _._2.entrust.time): _*
          ).take(maxSize)
        } else if (
          entrusts.values
            .count(_.status != EntrustStatus.submit) > historySize
        ) {
          entrusts
            .filter(_._2.status == EntrustStatus.submit) ++
            ListMap(
              entrusts
                .filter(_._2.status != EntrustStatus.submit)
                .toSeq
                .sortWith(_._2.entrust.time isAfter _._2.entrust.time): _*
            ).take(maxSize)
        } else entrusts
      }

    def marginFrozen(
        data: DataStore
    ): BigDecimal = {
      data.entrusts
        .filter(tp =>
          tp._2.status == EntrustStatus.submit && tp._2.entrust.offset == Offset.open
        )
        .map(_._2.entrust.marginFrozen)
        .sum
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
        case Run(_, _, _, _, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case Recovery => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case StreamComplete() => {
          Effect.none
        }
        case UpdateLeverRate(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case e @ IsCanChangeLeverRate() => {
          logger.info(command.logJson)
          Effect.none.thenRun((state: State) => {
            if (
              state.data.entrusts.values
                .exists(p => p.status == EntrustStatus.submit)
            ) {
              e.replyTo.tell(ChangeLeverRateNo())
            } else {
              e.replyTo.tell(ChangeLeverRateYes())
            }
          })
        }
        case e @ MarginQuery() => {
          logger.info(command.logJson)
          Effect.none.thenRun((updateState: State) => {
            e.replyTo.tell(MarginQueryOk(marginFrozen(state.data)))
          })
        }
        case CreateFutureOk(request) => {
          logger.info(command.logJson)
          Effect.none.thenRun((updateState: State) => {
            request.replyTo.tell(CreateOk(request))
            Source
              .future(
                sharding
                  .entityRefFor(
                    EntrustNotifyBehavior.typeKey,
                    state.data.config.entrustNotifyId
                  )
                  .ask[BaseSerializer](
                    EntrustNotifyBehavior.Push(
                      notif = NotifyModel.NotifyInfo(
                        orderId = request.orderId,
                        symbol = state.data.symbol,
                        contractType = state.data.contractType,
                        direction = state.data.direction,
                        offset = request.offset,
                        leverRate = state.data.leverRate,
                        orderPriceType = request.orderPriceType,
                        entrustStatus = EntrustStatus.submit,
                        source =
                          com.dounine.tractor.model.types.currency.Source.api,
                        orderType = OrderType.statement,
                        createTime = LocalDateTime.now(),
                        price = request.price,
                        volume = request.volume,
                        tradeVolume = 0,
                        tradeTurnover = 0,
                        fee = 0,
                        profit = 0,
                        trade = List(
                          NotifyModel.Trade(
                            tradeVolume = 1,
                            tradePrice = request.price,
                            tradeFee = 0,
                            tradeTurnover = 0,
                            createTime = LocalDateTime.now(),
                            role = Role.taker
                          )
                        )
                      )
                    )(_)
                  )(3.seconds)
              )
              .runWith(Sink.ignore)(materializer)
          })
        }
        case CreateFutureFail(request, status) => {
          logger.error(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              request.replyTo.tell(CreateFail(request, status))
            })
        }
        case e @ Create(
              orderId,
              offset,
              orderPriceType,
              price,
              volume
            ) => {
          logger.info(command.logJson)
          if (
            state.data.entrusts.count(
              _._2.status == EntrustStatus.submit
            ) > maxSize
          ) {
            Effect.none.thenRun((_: State) => {
              e.replyTo.tell(
                CreateFail(
                  e,
                  EntrustCreateFailStatus.createSizeOverflow
                )
              )
            })
          } else {
            Effect
              .persist(command)
              .thenRun((state: State) => {
                offset match {
                  case Offset.open => {
                    val accountBenefits =
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              AggregationBehavior.typeKey,
                              state.data.config.aggregationId
                            )
                            .ask[BaseSerializer](
                              AggregationBehavior
                                .Query(
                                  AggregationActor.position,
                                  state.data.phone,
                                  state.data.symbol
                                )(_)
                            )(3.seconds)
                        )
                        .flatMapConcat {
                          case AggregationBehavior.QueryOk(actors) => {
                            val balance = Source
                              .future(
                                ServiceSingleton
                                  .get(classOf[BalanceRepository])
                                  .balance(
                                    phone = state.data.phone,
                                    symbol = state.data.symbol
                                  )
                              )
                              .collect {
                                case Some(balance) => balance
                                case None =>
                                  throw new Exception("balance not found")
                              }
                              .map(_.balance)

                            val profitUnrealSource = Source(actors)
                              .mapAsync(4) { actor =>
                                {
                                  sharding
                                    .entityRefFor(
                                      PositionBase.typeKey,
                                      actor
                                    )
                                    .ask[BaseSerializer](
                                      PositionBase.ProfitUnrealQuery()(_)
                                    )(3.seconds)
                                }
                              }
                              .collect {
                                case PositionBase
                                      .ProfitUnrealQueryOk(profitUnreal) =>
                                  profitUnreal
                                case PositionBase
                                      .ProfitUnrealQueryFail(msg) => {
                                  logger.error(msg)
                                  BigDecimal(0)
                                }
                              }

                            profitUnrealSource.merge(balance)
                          }
                        }
                        .fold(BigDecimal(0))(_ + _)

                    val positionMargin =
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              AggregationBehavior.typeKey,
                              state.data.config.aggregationId
                            )
                            .ask[BaseSerializer](
                              AggregationBehavior
                                .Query(
                                  AggregationActor.position,
                                  state.data.phone,
                                  state.data.symbol
                                )(_)
                            )(3.seconds)
                        )
                        .flatMapConcat {
                          case AggregationBehavior.QueryOk(actors) => {
                            Source(actors)
                              .mapAsync(4) { actor =>
                                {
                                  sharding
                                    .entityRefFor(
                                      PositionBase.typeKey,
                                      actor
                                    )
                                    .ask[BaseSerializer](
                                      PositionBase.MarginQuery()(_)
                                    )(3.seconds)
                                }
                              }
                              .collect {
                                case PositionBase.MarginQueryOk(margin) =>
                                  margin
                                case PositionBase.MarginQueryFail(msg) => {
                                  logger.error(msg)
                                  BigDecimal(0)
                                }
                              }
                          }
                        }
                        .fold(BigDecimal(0))(_ + _)

                    val entrustMargin = Source
                      .future(
                        sharding
                          .entityRefFor(
                            AggregationBehavior.typeKey,
                            state.data.config.aggregationId
                          )
                          .ask[BaseSerializer](
                            AggregationBehavior
                              .Query(
                                AggregationActor.entrust,
                                state.data.phone,
                                state.data.symbol
                              )(_)
                          )(3.seconds)
                      )
                      .flatMapConcat {
                        case AggregationBehavior.QueryOk(actors) => {
                          Source(actors)
                            .filterNot(_ == state.data.entityId)
                            .mapAsync(4) { actor =>
                              {
                                sharding
                                  .entityRefFor(
                                    EntrustBase.typeKey,
                                    actor
                                  )
                                  .ask[BaseSerializer](
                                    EntrustBase.MarginQuery()(_)
                                  )(3.seconds)
                              }
                            }
                            .collect {
                              case EntrustBase.MarginQueryOk(margin) => margin
                              case EntrustBase.MarginQueryFail(msg) => {
                                logger.error(msg)
                                BigDecimal(0)
                              }
                            }
                        }
                      }
                      .merge(
                        Source.single(
                          marginFrozen(state.data)
                        )
                      )
                      .fold(BigDecimal(0))(_ + _)

                    val availableSecuredAssets = accountBenefits
                      .concat(
                        positionMargin
                          .merge(entrustMargin)
                          .fold(BigDecimal(0))(_ + _)
                      )
                      .reduce(_ - _)

                    availableSecuredAssets
                      .idleTimeout(3.seconds)
                      .map(balance => {
                        if (balance >= 0) {
                          CreateFutureOk(e)
                        } else {
                          CreateFutureFail(
                            e,
                            EntrustCreateFailStatus.createAvailableGuaranteeIsInsufficient
                          )
                        }
                      })
                      .recover {
                        case ee: Throwable => {
                          ee.printStackTrace()
                          CreateFutureFail(
                            e,
                            EntrustCreateFailStatus.createTimeout
                          )
                        }
                      }
                      .runWith(
                        ActorSink.actorRef(
                          ref = context.self,
                          onCompleteMessage = StreamComplete(),
                          onFailureMessage = ee => {
                            ee.printStackTrace()
                            CreateFutureFail(
                              e,
                              EntrustCreateFailStatus.createTimeout
                            )
                          }
                        )
                      )(materializer)
                  }
                  case Offset.close => {
                    val closeVolumn = state.data.entrusts
                      .filter(item =>
                        item._2.entrust.offset == Offset.close && item._2.status == EntrustStatus.submit
                      )
                      .map(_._2.entrust.volume)
                      .sum

                    Source
                      .future(
                        sharding
                          .entityRefFor(
                            PositionBase.typeKey,
                            state.data.config.positionId
                          )
                          .ask[BaseSerializer](
                            PositionBase.Query()(_)
                          )(3.seconds)
                      )
                      .collect {
                        case PositionBase.QueryOk(position) => position
                      }
                      .map {
                        case Some(position) => {
                          if (position.volume - closeVolumn >= 0) {
                            CreateFutureOk(e)
                          } else {
                            CreateFutureFail(
                              e,
                              EntrustCreateFailStatus.createPositionNotEnoughIsAvailable
                            )
                          }
                        }
                        case None =>
                          CreateFutureFail(
                            e,
                            EntrustCreateFailStatus.createPositionNotExit
                          )
                      }
                      .recover {
                        case ee: Throwable => {
                          ee.printStackTrace()
                          CreateFutureFail(
                            e,
                            EntrustCreateFailStatus.createTimeout
                          )
                        }
                      }
                      .runWith(
                        ActorSink.actorRef(
                          ref = context.self,
                          onCompleteMessage = StreamComplete(),
                          onFailureMessage = ee => {
                            ee.printStackTrace()
                            CreateFutureFail(
                              e,
                              EntrustCreateFailStatus.createTimeout
                            )
                          }
                        )
                      )(materializer)

                  }
                }
              })
          }
        }
        case e @ Cancel(orderId) => {
          logger.info(command.logJson)
          state.data.entrusts.get(orderId) match {
            case Some(entrust) =>
              entrust.status match {
                case EntrustStatus.submit =>
                  Effect
                    .persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              EntrustNotifyBehavior.typeKey,
                              state.data.config.entrustNotifyId
                            )
                            .ask[BaseSerializer](
                              EntrustNotifyBehavior.Push(
                                notif = NotifyModel.NotifyInfo(
                                  orderId = orderId,
                                  symbol = state.data.symbol,
                                  contractType = state.data.contractType,
                                  direction = state.data.direction,
                                  offset = entrust.entrust.offset,
                                  leverRate = state.data.leverRate,
                                  orderPriceType = OrderPriceType.limit,
                                  entrustStatus = EntrustStatus.canceled,
                                  source =
                                    com.dounine.tractor.model.types.currency.Source.api,
                                  orderType = OrderType.statement,
                                  createTime = LocalDateTime.now(),
                                  price = entrust.entrust.price,
                                  volume = entrust.entrust.volume,
                                  tradeVolume = 0,
                                  tradeTurnover = 0,
                                  fee = 0,
                                  profit = 0,
                                  trade = List(
                                    NotifyModel.Trade(
                                      tradeVolume = 1,
                                      tradePrice = 0,
                                      tradeFee = 0,
                                      tradeTurnover = 0,
                                      createTime = LocalDateTime.now(),
                                      role = Role.taker
                                    )
                                  )
                                )
                              )(_)
                            )(3.seconds)
                        )
                        .runWith(Sink.ignore)(materializer)
                    })
                case EntrustStatus.matchPart =>
                  Effect
                    .persist(command)
                    .thenRun((_: State) => {
                      e.replyTo.tell(CancelOk(orderId))
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              EntrustNotifyBehavior.typeKey,
                              state.data.config.entrustNotifyId
                            )
                            .ask[BaseSerializer](
                              EntrustNotifyBehavior.Push(
                                notif = NotifyModel.NotifyInfo(
                                  orderId = orderId,
                                  symbol = state.data.symbol,
                                  contractType = state.data.contractType,
                                  direction = state.data.direction,
                                  offset = entrust.entrust.offset,
                                  leverRate = state.data.leverRate,
                                  orderPriceType = OrderPriceType.limit,
                                  entrustStatus =
                                    EntrustStatus.matchPartOtherCancel,
                                  source =
                                    com.dounine.tractor.model.types.currency.Source.api,
                                  orderType = OrderType.statement,
                                  createTime = LocalDateTime.now(),
                                  price = entrust.entrust.price,
                                  volume = entrust.entrust.volume,
                                  tradeVolume = 0,
                                  tradeTurnover = 0,
                                  fee = 0,
                                  profit = 0,
                                  trade = List(
                                    NotifyModel.Trade(
                                      tradeVolume = 1,
                                      tradePrice = 0,
                                      tradeFee = 0,
                                      tradeTurnover = 0,
                                      createTime = LocalDateTime.now(),
                                      role = Role.taker
                                    )
                                  )
                                )
                              )(_)
                            )(3.seconds)
                        )
                        .runWith(Sink.ignore)(materializer)
                    })
                case EntrustStatus.matchPartOtherCancel =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          EntrustCancelFailStatus.cancelAlreadyMatchPartCancel
                        )
                      )
                    })
                case EntrustStatus.canceled =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          EntrustCancelFailStatus.cancelAlreadyCanceled
                        )
                      )
                    })
                case EntrustStatus.matchAll =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          EntrustCancelFailStatus.cancelAlreadyMatchAll
                        )
                      )
                    })
                case EntrustStatus.error =>
                  Effect.none
                    .thenRun((_: State) => {
                      e.replyTo.tell(
                        CancelFail(
                          orderId,
                          EntrustCancelFailStatus.cancelAlreadyFailed
                        )
                      )
                    })
              }
            case None =>
              Effect.none.thenRun((_: State) => {
                e.replyTo.tell(
                  CancelFail(
                    orderId,
                    EntrustCancelFailStatus.cancelOrderNotExit
                  )
                )
              })
          }
        }
        case MarketTradeBehavior.SubOk(_) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MarketTradeBehavior.TradeDetail(_, _, _, price, _, _) => {
          logger.info(command.logJson)
          val entrusts = state.data.entrusts
            .filter(_._2.status == EntrustStatus.submit)
            .filter(item => {
              val info = item._2.entrust
              (info.offset, state.data.direction) match {
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
            Source(entrusts)
              .log("position create")
              .runForeach(entrust => {
                val info = entrust._2
                Source
                  .future(
                    sharding
                      .entityRefFor(
                        PositionBase.typeKey,
                        state.data.config.positionId
                      )
                      .ask[BaseSerializer](ref =>
                        PositionBase.Create(
                          offset = info.entrust.offset,
                          volume = info.entrust.volume,
                          latestPrice = price
                        )(ref)
                      )(3.seconds)
                  )
                  .map {
                    case PositionBase.OpenOk()           => EntrustOk(entrust)
                    case PositionBase.MergeOk()          => EntrustOk(entrust)
                    case PositionBase.CloseOk()          => EntrustOk(entrust)
                    case PositionBase.CreateFail(status) => EntrustFail(entrust)
                  }
                  .runWith(
                    ActorSink.actorRef(
                      ref = context.self,
                      onCompleteMessage = StreamComplete(),
                      onFailureMessage = ee => {
                        ee.printStackTrace()
                        EntrustFail(entrust)
                      }
                    )
                  )(materializer)
              })(materializer)
          }
          Effect.none
        }
        case EntrustFail(info) => {
          logger.error(command.logJson)
          Effect.persist(command)
        }
        case EntrustOk(info) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((updateState: State) => {
              val entrust = info._2.entrust
              Source
                .future(
                  sharding
                    .entityRefFor(
                      EntrustNotifyBehavior.typeKey,
                      state.data.config.entrustNotifyId
                    )
                    .ask[BaseSerializer](ref =>
                      EntrustNotifyBehavior.Push(
                        notif = NotifyModel.NotifyInfo(
                          orderId = info._1,
                          symbol = state.data.symbol,
                          contractType = state.data.contractType,
                          direction = state.data.direction,
                          offset = entrust.offset,
                          leverRate = state.data.leverRate,
                          orderPriceType = OrderPriceType.limit,
                          entrustStatus = EntrustStatus.matchAll,
                          source =
                            com.dounine.tractor.model.types.currency.Source.api,
                          orderType = OrderType.statement,
                          createTime = LocalDateTime.now(),
                          price = entrust.price,
                          volume = entrust.volume,
                          tradeVolume = 0,
                          tradeTurnover = 0,
                          fee = 0,
                          profit = 0,
                          trade = List(
                            NotifyModel.Trade(
                              tradeVolume = 1,
                              tradePrice = 0,
                              tradeFee = 0,
                              tradeTurnover = 0,
                              createTime = LocalDateTime.now(),
                              role = Role.taker
                            )
                          )
                        )
                      )(ref)
                    )(3.seconds)
                )
                .runWith(
                  Sink.ignore
                )(materializer)
            })
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
          case CreateFutureFail(request, status) => {
            Idle(
              state.data.copy(
                entrusts = state.data.entrusts.map(entrust => {
                  if (entrust._1 == request.orderId) {
                    entrust.copy(_2 =
                      entrust._2.copy(
                        status = EntrustStatus.error
                      )
                    )
                  } else entrust
                })
              )
            )
          }
          case Create(
                orderId,
                offset,
                orderPriceType,
                price,
                volume
              ) => {
            Idle(
              state.data.copy(
                entrusts = state.data.entrusts ++ Map(
                  orderId -> EntrustInfo(
                    entrust = EntrustItem(
                      offset = offset,
                      orderPriceType = orderPriceType,
                      price = price,
                      marginFrozen =
                        state.data.contractSize * volume / price / state.data.leverRate.toString.toInt,
                      volume = volume,
                      time = LocalDateTime.now()
                    ),
                    status = EntrustStatus.submit
                  )
                )
              )
            )
          }
          case Cancel(orderId) => {
            Idle(
              state.data.copy(
                entrusts = state.data.entrusts.map(item => {
                  if (item._1 == orderId) {
                    (
                      orderId,
                      item._2.copy(
                        status = EntrustStatus.canceled
                      )
                    )
                  } else item
                })
              )
            )
          }
          case MarketTradeBehavior.SubOk(source) => {
            source
              .throttle(
                1,
                config.getDuration("engine.trigger.speed").toMillis.milliseconds
              )
              .buffer(1, OverflowStrategy.dropHead)
              .runWith(Sink.foreach(context.self.tell))(materializer)
            state
          }
          case EntrustOk(info) => {
            Idle(
              state.data.copy(
                entrusts = cropEntrusts(state.data.entrusts) ++ Map(
                  info.copy(
                    _2 = info._2.copy(
                      status = EntrustStatus.matchAll
                    )
                  )
                )
              )
            )
          }
          case EntrustFail(info) => {
            Idle(
              state.data.copy(
                entrusts = cropEntrusts(state.data.entrusts) ++ Map(
                  info.copy(
                    _2 = info._2.copy(
                      status = EntrustStatus.error
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
