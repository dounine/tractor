package com.dounine.tractor.behaviors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.stream.SystemMaterializer
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
        val http = Http(context.system)

        def data(
            actors: Map[String, AggregationActor]
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Up(actor, id) => {
              logger.info(e.logJson)
              data(actors ++ Map(id -> actor))
            }
            case e @ Down(actor, id) => {
              logger.info(e.logJson)
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
