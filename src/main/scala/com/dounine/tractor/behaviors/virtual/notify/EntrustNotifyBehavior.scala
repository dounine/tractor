package com.dounine.tractor.behaviors.virtual.notify

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.stream.scaladsl.{BroadcastHub, Source, StreamRefs}
import akka.stream.{
  OverflowStrategy,
  QueueCompletionResult,
  QueueOfferResult,
  SourceRef,
  SystemMaterializer
}
import com.dounine.tractor.model.models.{BaseSerializer, NotifyModel}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.currency.{Direction, Offset}
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.LoggerFactory

object EntrustNotifyBehavior extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(EntrustNotifyBehavior.getClass)
  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("EntrustNotifyBehavior")

  trait Command extends BaseSerializer

  case class Sub(
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction
  )(val replyTo: ActorRef[BaseSerializer])
      extends Command

  case class SubOk(source: SourceRef[Receive]) extends Command

  case class SubFail(msg: String) extends Command

  case class Push(notif: NotifyModel.NotifyInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  case class Receive(notif: NotifyModel.NotifyInfo) extends Command

  case class PushOk() extends Command

  case class PushFail(result: QueueOfferResult) extends Command

  def apply(): Behavior[BaseSerializer] =
    Behaviors.setup[BaseSerializer] { context =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        val (subQueue, subSource) = Source
          .queue[NotifyModel.NotifyInfo](
            100,
            OverflowStrategy.fail
          )
          .preMaterialize()

        val brocastHub = subSource.runWith(BroadcastHub.sink)

        Behaviors.receiveMessage {
          case e @ Push(notif) => {
            logger.info(e.logJson)
            subQueue
              .offer(
                notif.copy(
                  direction = notif.offset match {
                    case Offset.open  => notif.direction
                    case Offset.close => Direction.reverse(notif.direction)
                  }
                )
              )
              .map {
                case result: QueueCompletionResult => {
                  logger.info("Completion")
                  e.replyTo.tell(PushFail(result))
                }
                case QueueOfferResult.Enqueued => {
                  logger.info("Enqueued")
                  e.replyTo.tell(PushOk())
                }
                case QueueOfferResult.Dropped => {
                  logger.info("Dropped")
                  e.replyTo.tell(PushFail(QueueOfferResult.Dropped))
                }
              }(context.executionContext)
            Behaviors.same
          }
          case e @ Sub(symbol, contractType, direction) => {
            logger.info(e.logJson)
            val sourceRef: SourceRef[Receive] = brocastHub
              .filter(detail =>
                detail.symbol == symbol && detail.contractType == contractType && (detail.offset match {
                  case Offset.open => detail.direction == direction
                  case Offset.close =>
                    Direction.reverse(detail.direction) == direction
                })
              )
              .map(Receive(_))
              .runWith(StreamRefs.sourceRef())
            e.replyTo.tell(SubOk(sourceRef))
            Behaviors.same
          }
        }
      }
    }

}
