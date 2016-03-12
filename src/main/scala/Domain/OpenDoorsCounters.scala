package Domain

import Cqrs.Aggregate._
import Cqrs.Database.EventData
import Cqrs.Projection
import Domain.Counter.CounterAggregate
import Domain.Door.DoorAggregate
import Domain.{OpenDoorsCountersProjectionData => P}

import scala.collection.immutable.TreeMap

object OpenDoorsCountersProjectionData {

  final case class DoorState(counters: TreeMap[AggregateId, Int])

  final case class Data(nextDoor: Option[AggregateId], doorCounters: TreeMap[AggregateId, P.DoorState])

}

object OpenDoorsCountersProjection extends Projection[P.Data] {
  def initialData = P.Data(None, TreeMap.empty)

  val listeningFor = List(CounterAggregate.tag, DoorAggregate.tag)

  def accept[E](d: Data) = {
    case EventData(_, id, _, Door.Registered(_)) => P.Data(Some(id), d.doorCounters.updated(id, P.DoorState(TreeMap.empty)))
    case EventData(_, id, _, Door.Closed) => d.copy(nextDoor = None)
    case EventData(_, id, _, Door.Opened) => P.Data(Some(id), init(d.doorCounters, id, P.DoorState(TreeMap.empty)))
    case EventData(_, id, _, Counter.Incremented) =>
      d.nextDoor match {
        case Some(doorId) => updateDoorCounter(d, doorId, id, 1, (c: Int) => c + 1)
        case _ => d
      }
    case EventData(_, id, _, Counter.Decremented) =>
      d.nextDoor match {
        case Some(doorId) => updateDoorCounter(d, doorId, id, -1, (c: Int) => c - 1)
        case _ => d
      }
  }

  private def modify[K, V](kv: TreeMap[K, V], k: K, f: Option[V] => V): TreeMap[K, V] = kv.updated(k, (f compose kv.get) (k))

  private def modify[K, V](kv: TreeMap[K, V], k: K, init: => V, f: V => V): TreeMap[K, V] = modify(kv, k, (x: Option[V]) => x match {
    case Some(v) => f(v);
    case None => init
  })

  private def init[K, V](kv: TreeMap[K, V], k: K, init: => V): TreeMap[K, V] = modify(kv, k, init, identity[V])

  private def updateDoorCounter(d: P.Data, doorId: AggregateId, counterId: AggregateId, init: => Int, update: Int => Int): Data =
    d.copy(
      doorCounters = modify(
        d.doorCounters,
        doorId,
        P.DoorState(TreeMap.empty),
        (ds: P.DoorState) => P.DoorState(
          modify(
            ds.counters,
            counterId,
            init,
            update
          )
        )
      )
    )
}

