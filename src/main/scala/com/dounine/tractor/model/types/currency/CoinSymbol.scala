package com.dounine.tractor.model.types.currency

object CoinSymbol extends Enumeration {
  type CoinSymbol = Value

  val dbLength: Int = 5

  val BTC: CoinSymbol.Value = Value("BTC")
  val ETC: CoinSymbol.Value = Value("ETC")
  val ETH: CoinSymbol.Value = Value("ETH")
  val TRX: CoinSymbol.Value = Value("TRX")
  val XRP: CoinSymbol.Value = Value("XRP")
  val BCH: CoinSymbol.Value = Value("BCH")
  val LTC: CoinSymbol.Value = Value("LTC")
  val EOS: CoinSymbol.Value = Value("EOS")
  val BSV: CoinSymbol.Value = Value("BSV")
  val ADA: CoinSymbol.Value = Value("ADA")
  val LINK: CoinSymbol.Value = Value("LINK")

  def list: List[CoinSymbol] = List(BTC, ETC, ETH, TRX, XRP, BCH, LTC, EOS, BSV, ADA, LINK)

}
