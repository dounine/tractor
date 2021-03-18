package com.dounine.tractor.model.types.currency

object AggregationActor extends Enumeration {
  type AggregationActor = Value
  val updown: AggregationActor.Value = Value("aggregation_actor_updown")
  val position: AggregationActor.Value = Value("aggregation_actor_position")
  val entrust: AggregationActor.Value = Value("aggregation_actor_entrust")
  val trigger: AggregationActor.Value = Value("aggregation_actor_trigger")
}
