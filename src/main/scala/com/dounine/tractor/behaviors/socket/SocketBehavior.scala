package com.dounine.tractor.behaviors.socket

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.{
  KillSwitches,
  OverflowStrategy,
  SystemMaterializer,
  UniqueKillSwitch
}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.typed.scaladsl.ActorSource
import akka.util.Timeout
import com.dounine.tractor.behaviors.slider.SliderBehavior
import com.dounine.tractor.behaviors.updown.UpDownBase
import com.dounine.tractor.model.models.{BaseSerializer, MessageModel}
import com.dounine.tractor.model.types.currency.{LeverRate, UpDownUpdateType}
import com.dounine.tractor.model.types.service.MessageType.MessageType
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

  final case class StreamFail(e: Throwable) extends Command

  final case object Ack extends Command

  final case class MessageReceive(
      actor: ActorRef[Command],
      message: String
  ) extends Command

  final case class MessageOutput[T](
      `type`: MessageType,
      data: T
  ) extends Command

  final case class Config(
      userId: String = "",
      updownId: String = ""
  ) extends BaseSerializer

  final case class SubConfig(
      updown: Option[UniqueKillSwitch] = Option.empty,
      slider: Option[UniqueKillSwitch] = Option.empty
  ) extends BaseSerializer

  final case class DataStore(
      phone: Option[String],
      client: Option[ActorRef[BaseSerializer]],
      config: Config,
      subConfig: SubConfig = SubConfig()
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
                      data.subConfig.slider.foreach(_.shutdown())
                      val sliderInfo =
                        upDown.data.toJson.jsonTo[MessageModel.UpDownSlider]

                      val sliderBehavior = context.spawnAnonymous(
                        SliderBehavior(
                          phone = data.phone.get,
                          symbol = sliderInfo.symbol,
                          contractType = sliderInfo.contractType,
                          direction = sliderInfo.direction,
                          offset = sliderInfo.offset
                        )
                      )
                      import akka.actor.typed.scaladsl.AskPattern._

                      val (killSwitch, subSource) = Source
                        .future(
                          sliderBehavior.ask((ref: ActorRef[BaseSerializer]) =>
                            SliderBehavior.Sub()(ref)
                          )(3.seconds, context.system.scheduler)
                        )
                        .flatMapConcat {
                          case SliderBehavior.SubOk(source) => source
                          case SliderBehavior.SubFail(msg) => {
                            logger.error(msg)
                            Source.empty[SliderBehavior.Push]
                          }
                        }
                        .viaMat(KillSwitches.single)(Keep.right)
                        .preMaterialize()(materializer)

                      subSource.runForeach(push => {
                        data.client.foreach(client => {
                          client.tell(
                            MessageOutput[Map[String, Any]](
                              `type` = MessageType.upDown,
                              data = Map(
                                "ctype" -> UpDownMessageType.slider,
                                "data" -> push
                              )
                            )
                          )
                        })
                      })(materializer)

                      logined(
                        data.copy(
                          subConfig = data.subConfig.copy(
                            slider = Option(killSwitch)
                          )
                        )
                      )

                      Behaviors.same
                    }
                    case UpDownMessageType.update => {
                      val updateInfo =
                        upDown.data.toJson.jsonTo[MessageModel.UpDownUpdate]
                      val updateValue: Any = updateInfo.name match {
                        case UpDownUpdateType.run |
                            UpDownUpdateType.closeZoom => {
                          updateInfo.value == "true"
                        }
                        case UpDownUpdateType.closeTriggerPriceSpread |
                            UpDownUpdateType.closeReboundPrice |
                            UpDownUpdateType.closeTriggerPrice |
                            UpDownUpdateType.openTriggerPriceSpread |
                            UpDownUpdateType.openTriggerPrice |
                            UpDownUpdateType.openReboundPrice => {
                          BigDecimal(updateInfo.value)
                        }
                        case UpDownUpdateType.closeGetInProfit |
                            UpDownUpdateType.openVolume |
                            UpDownUpdateType.closeVolume => {
                          updateInfo.value.toInt
                        }
                        case UpDownUpdateType.openEntrustTimeout |
                            UpDownUpdateType.closeScheduling |
                            UpDownUpdateType.openScheduling |
                            UpDownUpdateType.closeEntrustTimeout => {
                          updateInfo.value.toInt.seconds
                        }
                        case UpDownUpdateType.openLeverRate => {
                          LeverRate.withName(updateInfo.value)
                        }
                      }
                      Source
                        .future(
                          sharding
                            .entityRefFor(
                              UpDownBase.typeKey,
                              UpDownBase.createEntityId(
                                phone = data.phone.get,
                                symbol = updateInfo.symbol,
                                contractType = updateInfo.contractType,
                                direction = updateInfo.direction
                              )
                            )
                            .ask((ref: ActorRef[BaseSerializer]) =>
                              UpDownBase.Update(
                                name = updateInfo.name,
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
                        .recover {
                          case ee: Throwable => {
                            ee.printStackTrace()
                            Ack
                          }
                        }
                        .runForeach(result => {
                          actor.tell(result)
                        })(materializer)

                      Behaviors.same
                    }
                    case UpDownMessageType.sub => {
                      data.subConfig.updown.foreach(_.shutdown())
                      actor.tell(Ack)
                      val info =
                        upDown.data.toJson.jsonTo[MessageModel.UpDownInfo]

                      val (killSwitch, subSource) = Source
                        .future(
                          sharding
                            .entityRefFor(
                              UpDownBase.typeKey,
                              UpDownBase.createEntityId(
                                phone = data.phone.get,
                                symbol = info.symbol,
                                contractType = info.contractType,
                                direction = info.direction
                              )
                            )
                            .ask((ref: ActorRef[BaseSerializer]) =>
                              UpDownBase.Sub()(ref)
                            )(3.seconds)
                        )
                        .flatMapConcat {
                          case UpDownBase.SubOk(source) => source
                          case UpDownBase.SubFail(msg) => {
                            logger.error(msg)
                            Source.empty[UpDownBase.PushDataInfo]
                          }
                        }
                        .viaMat(KillSwitches.single)(Keep.right)
                        .preMaterialize()(materializer)

                      subSource
                        .runForeach(info => {
                          data.client.foreach(client => {
                            val updownInfo = info.info
                            client.tell(
                              MessageOutput[Map[String, Any]](
                                `type` = MessageType.upDown,
                                data = Map(
                                  "ctype" -> "info",
                                  "data" -> Map(
                                    "status" -> updownInfo.status,
                                    "leverRate" -> updownInfo.openLeverRate,
                                    "open" -> Map(
                                      "run" -> updownInfo.run,
                                      "runLoading" -> updownInfo.runLoading,
                                      "rebound" -> updownInfo.openReboundPrice,
                                      "scheduling" -> updownInfo.openScheduling
                                        .map(_._1),
                                      "spread" -> updownInfo.openTriggerPriceSpread,
                                      "timeout" -> updownInfo.openEntrustTimeout
                                        .map(_._1),
                                      "volume" -> updownInfo.openVolume
                                    ),
                                    "close" -> Map(
                                      "rebound" -> updownInfo.closeReboundPrice,
                                      "spread" -> updownInfo.closeTriggerPriceSpread,
                                      "timeout" -> updownInfo.closeEntrustTimeout
                                        .map(_._1),
                                      "volume" -> updownInfo.closeVolume,
                                      "zoom" -> updownInfo.closeZoom
                                    )
                                  )
                                )
                              )
                            )
                          })
                        })(materializer)

                      logined(
                        data.copy(
                          subConfig = data.subConfig.copy(
                            updown = Option(killSwitch)
                          )
                        )
                      )
                    }
                  }
                }
              }
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
