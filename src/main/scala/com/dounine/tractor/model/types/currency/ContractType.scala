package com.dounine.tractor.model.types.currency

object ContractType extends Enumeration {
  type ContractType = Value
  val quarter: ContractType.Value = Value("quarter")
  val nextQuarter: ContractType.Value = Value("next_quarter")
  val thisWeek: ContractType.Value = Value("this_week")
  val nextWeek: ContractType.Value = Value("next_week")

  val dbLength: Int = 20

  def list: Vector[ContractType] = Vector(quarter, nextQuarter, thisWeek, nextWeek)

  def getAlias(contractType: ContractType): String = {
    contractType match {
      case ContractType.`quarter` => "CQ"
      case ContractType.`nextQuarter` => "NQ"
      case ContractType.`thisWeek` => "CW"
      case ContractType.`nextWeek` => "NW"
    }
  }

  /**
   * @param alias "CQ" | "NQ" | "CW" | "NW"
   * @return quarter | nextQuarter | thisWeek | nextWeek
   */
  def getReverAlias(alias: String): ContractType = {
    alias match {
      case "CQ" => ContractType.quarter
      case "NQ" => ContractType.nextQuarter
      case "CW" => ContractType.thisWeek
      case "NW" => ContractType.nextWeek
    }
  }

}
