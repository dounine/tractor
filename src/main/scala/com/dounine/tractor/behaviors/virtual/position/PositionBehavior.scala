package com.dounine.tractor.behaviors.virtual.position

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.{BroadcastHub, SourceQueueWithComplete}
import com.dounine.tractor.behaviors.AggregationBehavior
import com.dounine.tractor.behaviors.virtual.position.PositionBase._
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency._
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object PositionBehavior extends ActorSerializerSuport {
  private val logger = LoggerFactory.getLogger(PositionBehavior.getClass)

  def apply(
      entityId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      Behaviors.withTimers((timers: TimerScheduler[BaseSerializer]) => {
        val sharding = ClusterSharding(context.system)
        entityId.id.split("\\|").last.split("-", -1) match {
          case Array(
                phone,
                symbolStr,
                contractTypeStr,
                directionStr,
                randomId
              ) => {

            val materializer = SystemMaterializer(context.system).materializer
            val (rateInfoQueue, rateInfoSource) = akka.stream.scaladsl.Source
              .queue[RateInfo](
                2,
                OverflowStrategy.dropHead
              )
              .preMaterialize()(materializer)
            val rateInfoBrocastHub =
              rateInfoSource.runWith(BroadcastHub.sink)(materializer)

            val shareData = ShareData(
              rateInfoQueue = rateInfoQueue,
              rateInfoBrocastHub = rateInfoBrocastHub
            )

            val statusList = Seq(
              status.StopedStatus(context, shard, timers, shareData),
              status.IdleStatus(context, shard, timers, shareData),
              status.BusyStatus(context, shard, timers, shareData)
            )

            val commandDefaultHandler: (
                State,
                BaseSerializer
            ) => Effect[BaseSerializer, State] = (state, command) =>
              command match {
                case Stop => {
                  logger.info(command.logJson)
                  Effect.reply(shard)(
                    ClusterSharding.Passivate(entity = context.self)
                  )
                }
                case Shutdown => {
                  logger.info(command.logJson)
                  sharding
                    .entityRefFor(
                      AggregationBehavior.typeKey,
                      state.data.config.aggregationId
                    )
                    .tell(
                      AggregationBehavior.Down(
                        actor = AggregationActor.position,
                        state.data.entityId
                      )
                    )
                  Effect.none.thenStop()
                }
              }

            def eventDefaultHandler(
                state: State,
                command: BaseSerializer
            ): State = {
              command match {
                case _ => throw new Exception(s"unknown command ${command}")
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
                  position = Option.empty,
                  contractAdjustfactors = Seq.empty,
                  price = 0,
                  config = Config(),
                  phone = phone,
                  symbol = CoinSymbol.withName(symbolStr),
                  direction = Direction.withName(directionStr),
                  contractType = ContractType.withName(contractTypeStr),
                  leverRate = LeverRate.x20,
                  entityId = entityId.id.split("\\|").last,
                  contractSize = 0
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
              )
              .receiveSignal({
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
                  context.self.tell(Recovery)
                case (_, single) =>
                  logger.debug(single.logJson)
              })
              .snapshotWhen((state, event, _) => true)
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
