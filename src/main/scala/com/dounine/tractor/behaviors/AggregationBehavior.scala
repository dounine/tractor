package com.dounine.tractor.behaviors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.stream.scaladsl.{BroadcastHub, Source, StreamRefs}
import akka.stream.{OverflowStrategy, SourceRef, SystemMaterializer}
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.AggregationActor.AggregationActor
import com.dounine.tractor.model.types.currency.AggregationActorQueryStatus.AggregationActorQueryStatus
import com.dounine.tractor.tools.json.ActorSerializerSuport
import org.slf4j.LoggerFactory

object AggregationBehavior extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(MarketTradeBehavior.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("AggregationBehavior")

  trait Command extends BaseSerializer

  case class Up(actor: AggregationActor, id: String) extends Command

  case class Down(actor: AggregationActor, id: String) extends Command

  case class Sub(actor: AggregationActor)(val replyTo: ActorRef[BaseSerializer])
      extends Command

  case class SubOk(source: SourceRef[UpDownInfo]) extends Command

  case class UpDownInfo(
      isUp: Boolean,
      actor: AggregationActor,
      id: String
  ) extends BaseSerializer

  case class Query(actor: AggregationActor)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  case class QueryOk(actors: Seq[String]) extends Command

  case class QueryFail(status: AggregationActorQueryStatus) extends Command

  case object Shutdown extends Command

  case object SocketComplete extends Command

  def apply(): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        val (subQueue, subSource) = Source
          .queue[UpDownInfo](
            100,
            OverflowStrategy.fail
          )
          .preMaterialize()

        val brocastHub = subSource.runWith(BroadcastHub.sink)

        def data(
            actors: Map[String, AggregationActor]
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Sub(actor) => {
              logger.info(e.logJson)
              val source = brocastHub
                .filter(_.actor == actor)
                .runWith(StreamRefs.sourceRef())
              e.replyTo.tell(SubOk(source))
              Behaviors.same
            }
            case e @ Up(actor, id) => {
              logger.info(e.logJson)
              subQueue.offer(UpDownInfo(true, actor, id))
              data(actors ++ Map(id -> actor))
            }
            case e @ Down(actor, id) => {
              logger.info(e.logJson)
              subQueue.offer(UpDownInfo(false, actor, id))
              data(actors.filterNot(_ == (id, actor)))
            }
            case e @ Query(actor) => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(actors.filter(_._2 == actor).keys.toSeq)
              )
              Behaviors.same
            }
            case Shutdown => {
              Behaviors.stopped
            }
            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
          }

        data(Map.empty)
      }
    }

}
