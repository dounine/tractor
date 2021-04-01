package com.dounine.tractor.behaviors.socket

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.service.UserApi
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.dounine.tractor.tools.util.ServiceSingleton
import org.slf4j.LoggerFactory

object SocketBehavior extends ActorSerializerSuport {
  sealed trait Command extends BaseSerializer

  final case class Login(
      token: String,
      client: Option[ActorRef[BaseSerializer]]
  ) extends Command

  final case class LoginOk()

  final case object Shutdown extends Command

  final case class StreamComplete() extends Command

  final case class Config(
      userId: String = "",
      updownId: String = ""
  ) extends BaseSerializer

  final case class DataStore(
      phone: Option[String],
      client: Option[ActorRef[BaseSerializer]],
      config: Config
  ) extends BaseSerializer

  private final val logger = LoggerFactory.getLogger(SocketBehavior.getClass)

  def apply(): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val cluster = ClusterSharding(context.system)
        val materializer = SystemMaterializer(context.system).materializer
        val userService = ServiceSingleton.get(classOf[UserApi])
        def login(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Login(token, client) => {
              logger.info(e.logJson)
              userService.parse(token) match {
                case Some(session) => {
                  logined(
                    data.copy(
                      phone = Option(session.phone),
                      client = client
                    )
                  )
                }
                case None => Behaviors.stopped
              }
            }
            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
          }

        def logined(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
          }

        login(
          DataStore(
            phone = Option.empty,
            client = Option.empty,
            config = Config()
          )
        )
      }
    }

}
