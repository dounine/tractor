package com.dounine.tractor.behaviors.socket

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.tractor.behaviors.updown.UpDownBase
import com.dounine.tractor.model.models.{BaseSerializer, MessageModel}
import com.dounine.tractor.model.types.currency.{LeverRate, UpDownUpdateType}
import com.dounine.tractor.model.types.service.{MessageType, UpDownMessageType}
import com.dounine.tractor.service.UserApi
import com.dounine.tractor.tools.json.ActorSerializerSuport
import com.dounine.tractor.tools.util.ServiceSingleton
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object SocketBehavior extends ActorSerializerSuport {
  sealed trait Command extends BaseSerializer

  final case class Login(
      token: String,
      client: ActorRef[BaseSerializer]
  ) extends Command

  final case class LoginOk() extends Command

  final case object Shutdown extends Command

  final case class StreamComplete() extends Command

  final case object Ack extends Command

  final case class MessageReceive(
      actor: ActorRef[Command],
      message: String
  ) extends Command

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
        val sharding = ClusterSharding(context.system)
        val materializer = SystemMaterializer(context.system).materializer
        val userService = ServiceSingleton.get(classOf[UserApi])
        def login(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Login(token, client) => {
              logger.info(e.logJson)
              userService.parse(token) match {
                case Some(session) => {
                  client.tell(LoginOk())
                  logined(
                    data.copy(
                      phone = Option(session.phone),
                      client = Option(client)
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
            case e @ MessageReceive(actor, message) => {
              logger.info(e.logJson)
              val messageData =
                message.jsonTo[MessageModel.Data[Map[String, Any]]]

              messageData.`type` match {
                case MessageType.upDown => {
                  val upDown = messageData.data.toJson
                    .jsonTo[MessageModel.UpDownData[Map[String, Any]]]
                  upDown.ctype match {
                    case UpDownMessageType.slider => {

                    }
                    case UpDownMessageType.update => {
                      val update =
                        upDown.data.toJson.jsonTo[MessageModel.UpDownUpdate]
                      val updateValue = update.name match {
                        case UpDownUpdateType.run |
                            UpDownUpdateType.closeZoom => {
                          update.value == "true"
                        }
                        case UpDownUpdateType.status =>
                        case UpDownUpdateType.closeTriggerPriceSpread |
                            UpDownUpdateType.closeReboundPrice |
                            UpDownUpdateType.closeTriggerPrice |
                            UpDownUpdateType.openTriggerPriceSpread |
                            UpDownUpdateType.openTriggerPrice |
                            UpDownUpdateType.openReboundPrice => {
                          BigDecimal(update.value)
                        }
                        case UpDownUpdateType.closeGetInProfit |
                            UpDownUpdateType.openVolume |
                            UpDownUpdateType.closeVolume => {
                          update.value.toInt
                        }
                        case UpDownUpdateType.openEntrustTimeout =>
                        case UpDownUpdateType.closeScheduling |
                            UpDownUpdateType.openScheduling |
                            UpDownUpdateType.closeEntrustTimeout => {
                          update.value.toInt.seconds
                        }
                        case UpDownUpdateType.openLeverRate => {
                          LeverRate.withName(update.value)
                        }
                      }
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              UpDownBase.typeKey,
                              UpDownBase.createEntityId(
                                phone = data.phone.get,
                                symbol = update.symbol,
                                contractType = update.contractType,
                                direction = update.direction
                              )
                            )
                            .ask((ref: ActorRef[BaseSerializer]) =>
                              UpDownBase.Update(
                                name = update.name,
                                value = updateValue,
                                replyTo = ref
                              )
                            )(3.seconds)
                        )
                        .collect {
                          case UpDownBase.UpdateOk() => Ack
                          case UpDownBase.UpdateFail(msg) => {
                            logger.error(msg)
                            Ack
                          }
                        }
                        .recover{
                          case ee:Throwable => {
                            ee.printStackTrace()
                            Ack
                          }
                        }
                        .runForeach(result => {
                          actor.tell(result)
                        })(materializer)
                    }
                    case UpDownMessageType.info => {}
                  }
                }
              }
              Behaviors.same
            }
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
