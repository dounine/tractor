package com.dounine.tractor.behaviors.updown

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import com.dounine.tractor.model.models.{BaseSerializer, NotifyModel}
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction, LeverRate, UpDownStatus, UpDownUpdateType}
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.LoggerFactory
import UpDownBase._
import akka.stream.{OverflowStrategy, QueueCompletionResult, QueueOfferResult, SourceRef, SystemMaterializer}
import akka.stream.scaladsl.{BroadcastHub, Source, SourceQueueWithComplete, StreamRefs}
import com.dounine.tractor.behaviors.MarketTradeBehavior
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.tools.util.CopyUtil

import scala.concurrent.duration._

object UpDownBehavior extends JsonParse {
  private val logger = LoggerFactory.getLogger(UpDownBehavior.getClass)

  final case class ShareData(
                              infoQueue: SourceQueueWithComplete[PushDataInfo]
                            )

  def apply(
             entityId: PersistenceId,
             shard: ActorRef[ClusterSharding.ShardCommand]
           ): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      Behaviors.withTimers((timers: TimerScheduler[BaseSerializer]) => {
        entityId.id.split("\\|").last.split("-") match {
          case Array(phone, symbolStr, contractTypeStr, directionStr, randomId) => {
            val materializer = SystemMaterializer(context.system).materializer
            val (infoQueue, infoSource) = Source.queue[PushDataInfo](
              100,
              OverflowStrategy.fail
            )
              .preMaterialize()(materializer)
            val infoBrocastHub = infoSource.runWith(BroadcastHub.sink)(materializer)

            val shareData = ShareData(
              infoQueue = infoQueue
            )
            val statusList = Seq(
              status.StopedStatus(context, shard, timers, shareData),
              status.OpenTriggeringStatus(context, shard, timers, shareData),
              status.OpenEntrustedStatus(context, shard, timers, shareData),
              status.OpenPartMatchedStatus(context, shard, timers, shareData),
              status.OpenErroredStatus(context, shard, timers, shareData),
              status.OpenedStatus(context, shard, timers, shareData)
            )

            val commandDefaultHandler: (
              State,
                BaseSerializer
              ) => Effect[BaseSerializer, State] = (state, command) =>
              command match {
                case e@Query() => {
                  logger.info(command.logJson)
                  Effect.none.thenRun(_ => {
                    e.replyTo.tell(
                      msg = QuerySuccess(
                        status = UpDownStatus.withName(
                          state.getClass().getSimpleName()
                        ),
                        info = state.data
                      )
                    )
                  })
                }
                case Stop() => {
                  logger.info(command.logJson)
                  Effect.none
                }
                case Shutdown() => {
                  logger.info(command.logJson)
                  Effect.none.thenStop()
                }
                case StreamComplete() => {
                  logger.info(command.logJson)
                  Effect.none
                }
                case e@Sub() => {
                  logger.info(command.logJson)
                  Effect.none.thenRun((updateState: State) => {
                    val pushInfos = Map(
                      UpDownUpdateType.run -> updateState.data.info.run.toString,
                      UpDownUpdateType.runLoading -> updateState.data.info.runLoading,
                      UpDownUpdateType.status -> updateState.data.info,
                      UpDownUpdateType.openEntrustTimeout -> updateState.data.info.openEntrustTimeout,
                      UpDownUpdateType.openLeverRate -> updateState.data.leverRate,
                      UpDownUpdateType.openReboundPrice -> updateState.data.info.openReboundPrice,
                      UpDownUpdateType.openScheduling -> updateState.data.info.openScheduling,
                      UpDownUpdateType.openTriggerPriceSpread -> updateState.data.info.openTriggerPriceSpread,
                      UpDownUpdateType.openVolume -> updateState.data.info.openVolume,
                      UpDownUpdateType.closeEntrustTimeout -> updateState.data.info.closeEntrustTimeout,
                      UpDownUpdateType.closeReboundPrice -> updateState.data.info.closeReboundPrice,
                      UpDownUpdateType.closeScheduling -> updateState.data.info.closeScheduling,
                      UpDownUpdateType.closeTriggerPriceSpread -> updateState.data.info.closeTriggerPriceSpread,
                      UpDownUpdateType.closeZoom -> updateState.data.info.closeZoom,
                      UpDownUpdateType.closeVolume -> updateState.data.info.closeVolume
                    )
                    val info = PushDataInfo(
                      info = pushInfos.foldLeft(PushInfo())((sum, next) => {
                        CopyUtil.copy[PushInfo](sum)(
                          values = Map(next._1.toString -> Option(next._2))
                        )
                      })
                    )
                    val sourceRef: SourceRef[PushDataInfo] = infoBrocastHub
                      .concat(Source.single(info))
                      .runWith(StreamRefs.sourceRef())(materializer)
                    e.replyTo.tell(SubOk(sourceRef))
                  })
                }
                case Update(_, _, _) => {
                  logger.info(command.logJson)
                  Effect.persist(command)
                }
                case MarketTradeBehavior.TradeDetail(
                _, _, _, _, _, _
                ) => {
                  logger.info(command.logJson)
                  Effect.persist(command)
                }
              }

            def eventDefaultHandler(state: State, command: BaseSerializer)
            : State = {
              command match {
                case _ => {
                  val data: DataStore = state.data
                  CopyUtil.copy[State](state)(
                    values = Map(
                      "data" -> (
                        command match {
                          case Update(name, value, replyTo) => {
                            val copyInfo: Info = CopyUtil.copy[Info](data.info)(
                              values = Map(name.toString -> value)
                            )
                            if (name == UpDownUpdateType.openScheduling) {
                              timers.startSingleTimer(
                                key = triggerName,
                                msg = Trigger(),
                                delay = copyInfo.openScheduling
                              )
                            }
                            replyTo.tell(UpdateOk())
                            CopyUtil.copy[DataStore](data)(Map("info" -> copyInfo))
                          }
                          case e@MarketTradeBehavior.TradeDetail(
                          symbol: CoinSymbol,
                          contractType: ContractType,
                          direction: Direction,
                          price: Double,
                          amount: Int,
                          time: Long,
                          ) => {
                            logger.info(command.logJson)
                            data.preTradePrice match {
                              case Some(_) => data.copy(
                                tradePrice = Option(price),
                                preTradePrice = data.tradePrice
                              )
                              case None => data.copy(
                                tradePrice = Option(price),
                                preTradePrice = Option(price)
                              )
                            }
                          }
                        }
                        )
                    )
                  )
                }
              }
            }

            val commandHandler: (
              State,
                BaseSerializer
              ) => Effect[BaseSerializer, State] = (state, command) =>
              statusList.find(_._3 == state.getClass) match {
                case Some(status) =>
                  status._1(state, command, commandDefaultHandler)
                case None =>
                  throw new Exception(s"status unknown -> ${state.getClass}")
              }

            val eventHandler: (State, BaseSerializer) => State =
              (state, command) =>
                statusList.find(_._3 == state.getClass) match {
                  case Some(status) =>
                    status._2(state, command, eventDefaultHandler)
                  case None => throw new Exception("status unknown")
                }

            EventSourcedBehavior(
              persistenceId = entityId,
              emptyState = Stoped(
                data = DataStore(
                  config = Config(),
                  phone = phone,
                  symbol = CoinSymbol.withName(symbolStr),
                  contractType = ContractType.withName(contractTypeStr),
                  direction = Direction.withName(directionStr),
                  leverRate = LeverRate.x20,
                  userInfo = UserInfo()
                )
              ),
              commandHandler = commandHandler,
              eventHandler = eventHandler
            ).onPersistFailure(
              backoffStrategy = SupervisorStrategy
                .restartWithBackoff(
                  minBackoff = 1.seconds,
                  maxBackoff = 10.seconds,
                  randomFactor = 0.2
                )
                .withMaxRestarts(maxRestarts = 3)
                .withResetBackoffAfter(10.seconds)
            ).receiveSignal({
              case (state, RecoveryCompleted) =>
                logger.debug(
                  "Recovery Completed with state: {}",
                  state
                )
              case (state, RecoveryFailed(err)) =>
                logger.error(
                  "Recovery failed with: {}",
                  err.getMessage
                )
              case (state, SnapshotCompleted(meta)) =>
                logger.debug(
                  "Snapshot Completed with state: {},id({},{})",
                  state,
                  meta.persistenceId,
                  meta.sequenceNr
                )
              case (state, SnapshotFailed(meta, err)) =>
                logger.error(
                  "Snapshot failed with: {}",
                  err.getMessage
                )
              case (_, PreRestart) =>
                logger.info(s"PreRestart")
                context.self.tell(Recovery())
              case (_, single) =>
                logger.debug(single.logJson)
            }).snapshotWhen((state, event, _) => true)
              .withRetention(
                criteria = RetentionCriteria
                  .snapshotEvery(numberOfEvents = 2, keepNSnapshots = 1)
                  .withDeleteEventsOnSnapshot
              )
          }
        }

      })

    }

}
