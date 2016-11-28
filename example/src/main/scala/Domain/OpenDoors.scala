package Domain

import Cqrs.Aggregate._
import Cqrs.Database.EventData
import Cqrs.Projection

import scala.collection.immutable.TreeMap

object OpenDoors {

  final case class DoorState(counters: TreeMap[AggregateId, Int])

  final case class Data(nextDoor: Option[AggregateId], doorCounters: TreeMap[AggregateId, DoorState])

  def newCountersProjection = Projection.listeningFor(CounterAggregate.tag, DoorAggregate.tag).onEvent{ d: Data => {
      case EventData(_, id, _, Door.Registered(_)) => Data(Some(id), d.doorCounters.updated(id, DoorState(TreeMap.empty)))
      case EventData(_, id, _, Door.Closed) => d.copy(nextDoor = None)
      case EventData(_, id, _, Door.Opened) => Data(Some(id), init(d.doorCounters, id, DoorState(TreeMap.empty)))
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
    }}.startsWith(Data(None, TreeMap.empty))

    private def modify[K, V](kv: TreeMap[K, V], k: K, f: Option[V] => V): TreeMap[K, V] = kv.updated(k, (f compose kv.get) (k))

    private def modify[K, V](kv: TreeMap[K, V], k: K, init: => V, f: V => V): TreeMap[K, V] = modify(kv, k, (x: Option[V]) => x match {
      case Some(v) => f(v);
      case None => init
    })

    private def init[K, V](kv: TreeMap[K, V], k: K, init: => V): TreeMap[K, V] = modify(kv, k, init, identity[V])

    private def updateDoorCounter(d: Data, doorId: AggregateId, counterId: AggregateId, init: => Int, update: Int => Int): Data =
      d.copy(
        doorCounters = modify(
          d.doorCounters,
          doorId,
          DoorState(TreeMap.empty),
          (ds: DoorState) => DoorState(
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

