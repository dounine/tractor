package test.com.dounine.tractor.store

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import com.dounine.tractor.model.models.SliderModel
import com.dounine.tractor.model.types.currency.{
  CoinSymbol,
  ContractType,
  Direction
}
import com.dounine.tractor.model.types.service.SliderType
import com.dounine.tractor.service.SliderService
import com.dounine.tractor.store.{EnumMapper, SliderTable, UserTable}
import com.dounine.tractor.tools.akka.db.DataSource
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class SliderTableTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.extensions = ["com.dounine.tractor.tools.akka.db.DBSource"]
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          SliderTableTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          SliderTableTest
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
    with EnumMapper
    with JsonParse {

  val db = DataSource(system).source().db
  val dict = TableQuery[SliderTable]
  val sliderService = new SliderService(system)

  def beforeFun(): Unit = {
    try {
      Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
    } catch {
      case e =>
    }
    Await.result(db.run(dict.schema.createIfNotExists), Duration.Inf)
  }

  def afterFun(): Unit = {
    Await.result(db.run(dict.schema.truncate), Duration.Inf)
    Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
  }

  "slider table test" should {
    "add and query" in {
      beforeFun()
      val info = SliderModel.SliderInfo(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = false,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )

      sliderService.add(info).futureValue shouldBe Option(1)

      sliderService
        .info(
          phone = info.phone,
          symbol = info.symbol,
          contractType = info.contractType,
          direction = info.direction,
          sliderType = info.sliderType
        )
        .futureValue shouldBe info
      afterFun()
    }

    "query and group system" in {
      beforeFun()
      val userSliderInfo = SliderModel.SliderInfo(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = false,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )
      val systemSliderInfo = SliderModel.SliderInfo(
        phone = "111111111",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = true,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )

      sliderService.add(userSliderInfo).futureValue shouldBe Option(1)
      sliderService.add(systemSliderInfo).futureValue shouldBe Option(1)

      db.run(
          dict
            .filter(item =>
              item.phone === userSliderInfo.phone &&
                item.symbol === userSliderInfo.symbol &&
                item.contractType === userSliderInfo.contractType &&
                item.direction === userSliderInfo.direction &&
                item.system === false
            )
            .result
        )
        .flatMap(userResult => {
          val userNotFindConfigs = SliderType.list
            .filterNot(item => userResult.exists(_.sliderType == item))
            .toSet
          db.run(
              dict
                .filter(item =>
                  item.symbol === userSliderInfo.symbol &&
                    item.contractType === userSliderInfo.contractType &&
                    item.direction === userSliderInfo.direction &&
                    item.system === true &&
                    item.sliderType.inSet(userNotFindConfigs)
                )
                .result
            )
            .map(systemResult => {
              val list = (userResult ++ systemResult)
              val notFoundConfigs = SliderType.list
                .filterNot(item => list.exists(_.sliderType == item))
              println(notFoundConfigs.map(_.toString).mkString(","))
              list
                .map(item => {
                  (item.sliderType, item)
                })
                .toMap
            })(system.executionContext)
        })(system.executionContext)
        .futureValue shouldBe Map(userSliderInfo.sliderType -> userSliderInfo)
      afterFun()
    }

    "list method valid" in {
      beforeFun()
      val userSliderInfo = SliderModel.SliderInfo(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = false,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )
      val systemSliderInfo = SliderModel.SliderInfo(
        phone = "111111111",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = true,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )

      sliderService.add(userSliderInfo).futureValue shouldBe Option(1)
      sliderService.add(systemSliderInfo).futureValue shouldBe Option(1)

      assertThrows[Exception] {
        sliderService
          .info(
            phone = userSliderInfo.phone,
            symbol = userSliderInfo.symbol,
            contractType = userSliderInfo.contractType,
            direction = userSliderInfo.direction
          )
          .futureValue
      }

      SliderType.list
        .filterNot(_ == userSliderInfo.sliderType)
        .foreach(st => {
          sliderService
            .add(
              systemSliderInfo.copy(
                sliderType = st
              )
            )
            .futureValue shouldBe Option(1)
        })

      sliderService
        .info(
          phone = userSliderInfo.phone,
          symbol = userSliderInfo.symbol,
          contractType = userSliderInfo.contractType,
          direction = userSliderInfo.direction
        )
        .futureValue
        .size shouldBe SliderType.list.size

      afterFun()
    }

    "update exits" in {
      beforeFun()
      val userSliderInfo = SliderModel.SliderInfo(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = false,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )
      val systemSliderInfo = SliderModel.SliderInfo(
        phone = "111111111",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = true,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )

      sliderService.add(userSliderInfo).futureValue shouldBe Option(1)
      sliderService.add(systemSliderInfo).futureValue shouldBe Option(1)

      sliderService
        .update(
          userSliderInfo.copy(
            input = true
          )
        )
        .futureValue shouldBe 1

      sliderService
        .info(
          phone = userSliderInfo.phone,
          symbol = userSliderInfo.symbol,
          contractType = userSliderInfo.contractType,
          direction = userSliderInfo.direction,
          sliderType = userSliderInfo.sliderType
        )
        .futureValue shouldBe userSliderInfo.copy(
        input = true
      )

      afterFun()
    }

    "update not exits" in {
      beforeFun()
      val userSliderInfo = SliderModel.SliderInfo(
        phone = "123456789",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = false,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )
      val systemSliderInfo = SliderModel.SliderInfo(
        phone = "111111111",
        symbol = CoinSymbol.BTC,
        contractType = ContractType.quarter,
        direction = Direction.buy,
        system = true,
        sliderType = SliderType.openOnline,
        min = BigDecimal("0"),
        max = BigDecimal("100"),
        setup = BigDecimal("1"),
        disable = true,
        input = false,
        marks = Map("1USD" -> "1", "2USD" -> "2")
      )

      sliderService.add(systemSliderInfo).futureValue shouldBe Option(1)

      sliderService
        .update(userSliderInfo)
        .futureValue shouldBe 1

      sliderService
        .info(
          phone = userSliderInfo.phone,
          symbol = userSliderInfo.symbol,
          contractType = userSliderInfo.contractType,
          direction = userSliderInfo.direction,
          sliderType = userSliderInfo.sliderType
        )
        .futureValue shouldBe userSliderInfo

      afterFun()
    }

  }
}
