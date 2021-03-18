package com.dounine.tractor.model.types.currency

object AggregationActorQueryStatus extends Enumeration {
  type AggregationActorQueryStatus = Value
  val timeout: AggregationActorQueryStatus.Value = Value(
    "aggregation_actor_query_timeout"
  )
}
