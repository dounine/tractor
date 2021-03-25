package com.dounine.tractor.model.models

import com.dounine.tractor.model.types.currency.Direction.Direction

object MarketTradeModel {

  final case class WsData(
                           amount: Int,
                           direction: Direction,
                           id: Long,
                           price: BigDecimal,
                           ts: Long
                         )

  final case class WsTick(
                           id: Long,
                           ts: Long,
                           data: Seq[WsData]
                         )

  final case class WsPrice(
                            ch: String,
                            tick: WsTick,
                            ts: Long
                          ) extends BaseSerializer

}
