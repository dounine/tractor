package com.dounine.tractor.behaviors

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.stream.scaladsl.{BroadcastHub, Compression, Flow, Keep, RunnableGraph, Sink, Source, SourceQueueWithComplete, StreamRefs}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{KillSwitches, OverflowStrategy, QueueCompletionResult, QueueOfferResult, SourceRef, SystemMaterializer}
import akka.util.ByteString
import com.dounine.tractor.model.models.BaseSerializer
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.tools.akka.ConnectSettings
import com.dounine.tractor.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.LoggerFactory

object MarketTradeBehavior extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(MarketTradeBehavior.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] = EntityTypeKey[BaseSerializer]("MarketTradeBehavior")

  trait Event extends BaseSerializer

  case class Sub(symbol: CoinSymbol, contractType: ContractType)(val replyTo: ActorRef[BaseSerializer]) extends Event

  case class SubResponse(source: SourceRef[TradeDetail]) extends Event

  case class SendMessage(data: String) extends Event

  case class SocketConnect(url: Option[String] = Option.empty)(val replyTo: ActorRef[BaseSerializer]) extends Event

  case class SocketConnectAccept() extends Event

  case class SocketConnectReject(msg: Option[String]) extends Event

  case class SocketMessage(data: String) extends Event

  case class TradeDetail(
                          symbol: CoinSymbol,
                          contractType: ContractType,
                          direction: Direction,
                          price: Double,
                          amount: Int,
                          time: Long,
                        ) extends BaseSerializer

  case class SocketConnectFail(msg: String) extends Event

  case class SocketConnected(serverActor: ActorRef[Event]) extends Event

  case class SocketCloseFail(msg: String) extends Event

  case class SocketClosed(url: Option[String], msg: Option[String]) extends Event

  case object Shutdown extends Event

  case object SocketComplete extends Event

  def apply(): Behavior[BaseSerializer] = Behaviors.setup {
    context => {
      implicit val materializer = SystemMaterializer(context.system).materializer
      val http = Http(context.system)
      val source = ActorSource
        .actorRef[Event](
          completionMatcher = PartialFunction.empty,
          failureMatcher = PartialFunction.empty,
          bufferSize = 100,
          overflowStrategy = OverflowStrategy.fail
        )
        .viaMat(KillSwitches.single)(Keep.both)
        .collect {
          case SendMessage(text) => TextMessage(text)
        }
        .log("socket source error")
        .named("socket source")

      val sink = ActorSink
        .actorRef[Event](
          ref = context.self,
          onCompleteMessage = SocketClosed(None, None),
          onFailureMessage = (e: Throwable) => SocketCloseFail(e.getMessage)
        )

      val convertFlow = Flow[Message]
        .collect {
          case BinaryMessage.Strict(data) => {
            Source.single(data)
              .via(Compression.gunzip())
              .map(_.decodeString("ISO-8859-1"))
              .log("strict gunzip error")
              .runWith(Sink.head)
          }
          case BinaryMessage.Streamed(dataStream) => {
            dataStream
              .fold(ByteString.empty)(_ ++ _)
              .via(Compression.gunzip())
              .map(_.decodeString("ISO-8859-1"))
              .log("streamed gunzip error")
              .runWith(Sink.head)
          }
        }
        .mapAsync(4)(identity)
        .map(SocketMessage)
        .log("socket convert error")

      val flow = Flow.fromSinkAndSourceCoupledMat(
        sink = convertFlow.to(sink),
        source = source
      )(Keep.right)
        .named("socket flow")

      val socket = Source.queue[String](1)
        .flatMapConcat(url => {
          val (response, (serverActor, killSwitch)) = http.singleWebSocketRequest(
            request = WebSocketRequest(uri = Uri(url)),
            clientFlow = flow,
            settings = ConnectSettings.settings(context.system)
          )
          Source.future(response)
            .collect {
              case ValidUpgrade(response, chosenSubprotocol) => SocketConnected(serverActor)
              case InvalidUpgradeResponse(response, cause) => SocketConnectFail(cause)
            }
        })
        .to(Sink.foreach(context.self.tell))
        .run()

      val (subTradeDetailQueue, subTradeDetailSource) = Source.queue[TradeDetail](
        100,
        OverflowStrategy.dropHead
      )
        .preMaterialize()

      val brocastHub = subTradeDetailSource.runWith(BroadcastHub.sink)

      def data(serverActor: Option[ActorRef[Event]]): Behavior[BaseSerializer] = Behaviors.receiveMessage {
        case e@SocketConnect(url) => {
          logger.info(e.logJson)
          socket.offer(url.getOrElse("wss://api.hbdm.com/ws")) match {
            case result: QueueCompletionResult => e.replyTo.tell(SocketConnectReject(Option("queue completion")))
            case QueueOfferResult.Enqueued => e.replyTo.tell(SocketConnectAccept())
            case QueueOfferResult.Dropped => e.replyTo.tell(SocketConnectReject(Option("queue is full")))
          }
          Behaviors.same
        }
        case e@SocketConnected(actor) => {
          logger.info(e.logJson)
          data(Option(actor))
        }
        case e@SocketMessage(data) => {
          logger.info(e.toString)
          if (data.contains("ping")) {
            serverActor.foreach(_.tell(SendMessage(data.replace("ping", "pong"))))
          } else {
            subTradeDetailQueue.offer(data.jsonTo[TradeDetail])
          }
          Behaviors.same
        }
        case e@SocketClosed(url, msg) => {
          logger.info(e.logJson)
          Behaviors.same
        }
        case e@SocketCloseFail(msg) => {
          logger.info(e.logJson)
          Behaviors.same
        }
        case e@SocketConnectFail(msg) => {
          logger.info(e.logJson)
          Behaviors.same
        }
        case e@Sub(symbol, contractType) => {
          logger.info(e.logJson)
          val sourceRef: SourceRef[TradeDetail] = brocastHub
            .filter(detail => detail.symbol == symbol && detail.contractType == contractType)
            .runWith(StreamRefs.sourceRef())
          e.replyTo.tell(SubResponse(sourceRef))
          Behaviors.same
        }
        case e@Shutdown => {
          logger.info(e.logJson)
          Behaviors.stopped
        }
      }

      data(Option.empty)
    }
  }


}
