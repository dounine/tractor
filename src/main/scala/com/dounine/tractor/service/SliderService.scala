package com.dounine.tractor.service

import akka.actor.typed.ActorSystem
import com.dounine.tractor.model.models.SliderModel
import com.dounine.tractor.model.types.currency.CoinSymbol.CoinSymbol
import com.dounine.tractor.model.types.currency.ContractType.ContractType
import com.dounine.tractor.model.types.currency.Direction.Direction
import com.dounine.tractor.model.types.service.SliderType
import com.dounine.tractor.model.types.service.SliderType.SliderType
import com.dounine.tractor.store.{EnumMapper, SliderTable}
import com.dounine.tractor.tools.akka.db.DataSource
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future

class SliderService(system: ActorSystem[_]) extends SliderApi with EnumMapper {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[SliderTable] = TableQuery[SliderTable]

  override def add(info: SliderModel.SliderInfo): Future[Option[Int]] = {
    db.run(
      dict ++= Seq(info)
    )
  }

  override def info(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction,
      sliderType: SliderType
  ): Future[SliderModel.SliderInfo] = {
    db.run(
      dict
        .filter(item =>
          item.phone === phone &&
            item.symbol === symbol &&
            item.contractType === contractType &&
            item.direction === direction &&
            (item.system === false || item.system === true) &&
            item.sliderType === sliderType
        )
        .result
        .head
    )
  }

  override def info(
      phone: String,
      symbol: CoinSymbol,
      contractType: ContractType,
      direction: Direction
  ): Future[Map[SliderType, SliderModel.SliderInfo]] = {
    db.run(
        dict
          .filter(item =>
            item.phone === phone &&
              item.symbol === symbol &&
              item.contractType === contractType &&
              item.direction === direction &&
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
                item.symbol === symbol &&
                  item.contractType === contractType &&
                  item.direction === direction &&
                  item.system === true &&
                  item.sliderType.inSet(userNotFindConfigs)
              )
              .result
          )
          .map(systemResult => {
            val list = (userResult ++ systemResult)
            val notFoundConfigs = SliderType.list
              .filterNot(item => list.exists(_.sliderType == item))
            if (notFoundConfigs.nonEmpty) {
              throw new Exception(
                notFoundConfigs
                  .map(_.toString)
                  .mkString(",") + " sliderType not found"
              )
            }
            list
              .map(item => {
                (item.sliderType, item)
              })
              .toMap
          })(system.executionContext)
      })(system.executionContext)
  }

}
