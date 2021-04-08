package test.com.dounine.tractor.behavior

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.persistence.typed.PersistenceId
import akka.stream.BoundedSourceQueue
import akka.stream.scaladsl.{Compression, Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.dounine.tractor.behaviors.{AggregationBehavior, MarketTradeBehavior}
import com.dounine.tractor.behaviors.socket.SocketBehavior
import com.dounine.tractor.behaviors.updown.{UpDownBase, UpDownBehavior}
import com.dounine.tractor.behaviors.virtual.entrust.{
  EntrustBase,
  EntrustBehavior
}
import com.dounine.tractor.behaviors.virtual.notify.EntrustNotifyBehavior
import com.dounine.tractor.behaviors.virtual.position.{
  PositionBase,
  PositionBehavior
}
import com.dounine.tractor.behaviors.virtual.trigger.{
  TriggerBase,
  TriggerBehavior
}
import com.dounine.tractor.model.models.{
  BalanceModel,
  BaseSerializer,
  MessageModel,
  SliderModel,
  UserModel
}
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction,
  Offset,
  UpDownUpdateType
}
import com.dounine.tractor.model.types.service.{
  MessageType,
  SliderType,
  UpDownMessageType,
  UserStatus
}
import com.dounine.tractor.service.{BalanceApi, SliderApi, UserApi, UserService}
import com.dounine.tractor.tools.json.JsonParse
import com.dounine.tractor.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}
import test.com.dounine.tractor.virtual.UpDownTest

import java.time.{Clock, LocalDateTime}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

class SocketBehaviorTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          SocketBehaviorTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          SocketBehaviorTest
        ].getSimpleName}"
           |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with MockitoSugar
    with JsonParse {

  val portGlobal = new AtomicInteger(8200)
  val orderIdGlobal = new AtomicInteger(1)

  val sharding = ClusterSharding(system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import better.files._
    val files = Seq(
      file"/tmp/journal_${classOf[SocketBehaviorTest].getSimpleName}",
      file"/tmp/snapshot_${classOf[SocketBehaviorTest].getSimpleName}"
    )
    try {
      files.filter(_.exists).foreach(_.delete())
    } catch {
      case e =>
    }

    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(
      Entity(
        typeKey = MarketTradeBehavior.typeKey
      )(
        createBehavior = entityContext => MarketTradeBehavior()
      )
    )
    sharding.init(
      Entity(
        typeKey = AggregationBehavior.typeKey
      )(
        createBehavior = entityContext => AggregationBehavior()
      )
    )

    sharding.init(
      Entity(
        typeKey = PositionBase.typeKey
      )(
        createBehavior = entityContext =>
          PositionBehavior(
            PersistenceId.of(
              PositionBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = TriggerBase.typeKey
      )(
        createBehavior = entityContext =>
          TriggerBehavior(
            PersistenceId.of(
              TriggerBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = EntrustNotifyBehavior.typeKey
      )(
        createBehavior = entityContext => EntrustNotifyBehavior()
      )
    )

    sharding.init(
      Entity(
        typeKey = EntrustBase.typeKey
      )(
        createBehavior = entityContext =>
          EntrustBehavior(
            PersistenceId.of(
              EntrustBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = UpDownBase.typeKey
      )(
        createBehavior = entityContext =>
          UpDownBehavior(
            PersistenceId.of(
              UpDownBase.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

  }

  def createSocket(): (BoundedSourceQueue[Message], String) = {
    val socketPort = portGlobal.incrementAndGet()
    val (
      socketClient: BoundedSourceQueue[Message],
      source: Source[Message, NotUsed]
    ) = Source
      .queue[Message](10)
      .preMaterialize()
    val result = Flow.fromSinkAndSourceCoupledMat(
      sink = Flow[Message].to(Sink.ignore),
      source = source
    )(Keep.right)

    Await.result(
      Http(system)
        .newServerAt("0.0.0.0", socketPort)
        .bindFlow(handleWebSocketMessages(result))
        .andThen(_.get)(system.executionContext),
      Duration.Inf
    )

    (socketClient, socketPort.toString)
  }

  final val phone = "123456789"
  final val symbol = CoinSymbol.BTC
  final val contractType = ContractType.quarter
  final val direction = Direction.buy

  val pingMessage = (time: Option[Long]) =>
    Await.result(
      Source
        .single(s"""{"ping":${time.getOrElse(System.currentTimeMillis())}}""")
        .map(ByteString(_))
        .via(Compression.gzip)
        .runWith(Sink.head),
      Duration.Inf
    )
  val dataMessage = (data: String) =>
    Await.result(
      Source
        .single(data)
        .map(ByteString(_))
        .via(Compression.gzip)
        .runWith(Sink.head),
      Duration.Inf
    )

  "socket behavior test" should {

    "logined" in {
      val (socketClient, socketPort) = createSocket()
      val userApi = mock[UserApi]
      when(
        userApi.login(phone, Map.empty)
      ).thenAnswer(args => {
        val _phone = args.getArgument[String](0)
        val _data = args.getArgument[Map[String, String]](1)
        implicit val clock: Clock = Clock.systemUTC
        val claim: UserModel.Session =
          UserModel.Session(
            phone = _phone,
            data = Option(_data)
          )
        val time = System.currentTimeMillis() / 1000
        Jwt.encode(
          JwtHeader(JwtAlgorithm.HS256),
          JwtClaim(claim.toJson)
            .issuedAt(time)
            .expiresIn(60),
          "admin"
        )
      })
      val loginToken = userApi.login(phone, Map.empty)
      when(userApi.parse(loginToken))
        .thenAnswer(args => {
          val token = args.getArgument[String](0)
          if (Jwt.isValid(token.trim(), "admin", Seq(JwtAlgorithm.HS256))) {
            val result: Try[(String, String, String)] =
              Jwt.decodeRawAll(
                token.trim(),
                "admin",
                Seq(JwtAlgorithm.HS256)
              )
            Option(result.get._2.jsonTo[UserModel.Session])
          } else Option.empty
        })
      ServiceSingleton.put(classOf[UserApi], userApi)
      val mockBalanceService = mock[BalanceApi]
      when(mockBalanceService.balance(any, any)).thenAnswer(args =>
        Future.successful(
          Option(
            BalanceModel.Info(
              phone = args.getArgument[String](0),
              symbol = args.getArgument[CoinSymbol](1),
              balance = BigDecimal("1.0"),
              createTime = LocalDateTime.now()
            )
          )
        )
      )
      when(mockBalanceService.mergeBalance(any, any, any)).thenAnswer(args =>
        Future.successful(
          Option(
            BigDecimal("1.0")
          )
        )
      )
      ServiceSingleton.put(classOf[BalanceApi], mockBalanceService)

      val sliderApi = mock[SliderApi]
      when(
        sliderApi.info(
          phone = phone,
          symbol = symbol,
          contractType = contractType,
          direction = direction,
          sliderType = SliderType.openOnline
        )
      ).thenAnswer(args => {
        Future.successful(
          SliderModel.SliderInfo(
            phone = phone,
            symbol = symbol,
            contractType = contractType,
            direction = direction,
            sliderType = SliderType.openOnline,
            min = BigDecimal("0"),
            max = BigDecimal("100"),
            setup = BigDecimal("1"),
            system = false,
            disable = true,
            input = false,
            marks = Map.empty
          )
        )
      })
      ServiceSingleton.put(classOf[SliderApi], sliderApi)

      sharding.entityRefFor(EntrustNotifyBehavior.typeKey, socketPort)

      val marketTrade =
        sharding.entityRefFor(MarketTradeBehavior.typeKey, MarketTradeBehavior.typeKey.name)
      marketTrade.tell(
        MarketTradeBehavior.SocketConnect(
          Option(s"ws://127.0.0.1:${socketPort}")
        )(testKit.createTestProbe[BaseSerializer]().ref)
      )

      socketClient.offer(BinaryMessage(pingMessage(Option.empty)))

      val positionId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val positionBehavior =
        sharding.entityRefFor(PositionBase.typeKey, positionId)

      positionBehavior.tell(
        PositionBase.Run(
          marketTradeId = MarketTradeBehavior.typeKey.name,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val entrustId = EntrustBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val entrustBehavior =
        sharding.entityRefFor(EntrustBase.typeKey, entrustId)
      sharding.entityRefFor(AggregationBehavior.typeKey, socketPort)
      entrustBehavior.tell(
        EntrustBase.Run(
          marketTradeId = MarketTradeBehavior.typeKey.name,
          positionId = positionId,
          entrustNotifyId = socketPort,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val triggerId = TriggerBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction,
        randomId = socketPort
      )
      val triggerBehavior =
        sharding.entityRefFor(TriggerBase.typeKey, triggerId)

      triggerBehavior.tell(
        TriggerBase.Run(
          marketTradeId = MarketTradeBehavior.typeKey.name,
          entrustId = entrustId,
          aggregationId = socketPort,
          contractSize = 100
        )
      )

      val updownId = UpDownBase.createEntityId(
        phone = phone,
        symbol = symbol,
        contractType = contractType,
        direction = direction
      )
      val updownBehavior = sharding.entityRefFor(UpDownBase.typeKey, updownId)

      updownBehavior.tell(
        UpDownBase.Run(
          marketTradeId = MarketTradeBehavior.typeKey.name,
          entrustId = entrustId,
          triggerId = triggerId,
          entrustNotifyId = socketPort
        )
      )

      val socketBehavior = system.systemActorOf(SocketBehavior(), "socket")
      val client = testKit.createTestProbe[BaseSerializer]()
      socketBehavior.tell(
        SocketBehavior.Login(
          loginToken,
          client = client.ref
        )
      )
      client.expectMessage(SocketBehavior.LoginOk())

      val updownUpdate = testKit.createTestProbe[BaseSerializer]()
      socketBehavior.tell(
        SocketBehavior.MessageReceive(
          actor = updownUpdate.ref,
          message = MessageModel
            .Data[MessageModel.UpDownData[MessageModel.UpDownUpdate]](
              `type` = MessageType.upDown,
              data = MessageModel.UpDownData[MessageModel.UpDownUpdate](
                ctype = UpDownMessageType.update,
                data = MessageModel.UpDownUpdate(
                  symbol = CoinSymbol.BTC,
                  contractType = ContractType.quarter,
                  direction = Direction.buy,
                  name = UpDownUpdateType.openReboundPrice,
                  value = "10"
                )
              )
            )
            .toJson
        )
      )
      updownUpdate.expectMessage(SocketBehavior.Ack)


      val sliderProbe = testKit.createTestProbe[BaseSerializer]()
      socketBehavior.tell(
        SocketBehavior.MessageReceive(
          actor = sliderProbe.ref,
          message = MessageModel
            .Data[MessageModel.UpDownData[MessageModel.UpDownSlider]](
              `type` = MessageType.upDown,
              data = MessageModel.UpDownData[MessageModel.UpDownSlider](
                ctype = UpDownMessageType.slider,
                data = MessageModel.UpDownSlider(
                  symbol = symbol,
                  contractType = contractType,
                  direction = direction,
                  offset = Offset.open
                )
              )
            )
            .toJson
        )
      )
      sliderProbe.expectMessage(SocketBehavior.Ack)

    }

  }
}
