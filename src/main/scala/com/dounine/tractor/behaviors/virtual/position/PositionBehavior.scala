package com.dounine.tractor.behaviors.virtual.position

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import com.dounine.tractor.model.models.BaseSerializer
import PositionBase._
import com.dounine.tractor.model.types.currency.{CoinSymbol, ContractType, Direction, LeverRate}
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

        entityId.id.split("\\|").last.split("-") match {
          case Array(phone, symbolStr, contractTypeStr, directionStr, randomId) => {

            val statusList = Seq(
              status.StopedStatus(context, shard, timers),
              status.IdleStatus(context, shard, timers),
              status.BusyStatus(context, shard, timers)
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
                  Effect.none.thenStop()
                }
              }

            def eventDefaultHandler(state: State, command: BaseSerializer)
            : State = {
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
                  config = Config(),
                  phone = phone,
                  symbol = CoinSymbol.withName(symbolStr),
                  direction = Direction.withName(directionStr),
                  contractType = ContractType.withName(contractTypeStr),
                  leverRate = LeverRate.x20,
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
                context.self.tell(Recovery)
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
