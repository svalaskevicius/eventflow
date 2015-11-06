package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }

import scala.collection.immutable.TreeMap

object OpenDoorsCountersProjection {

  final case class DoorState(counters: TreeMap[AggregateId, Int])

  final case class Data(nextDoor: Option[AggregateId], doorCounters: TreeMap[AggregateId, DoorState])

  def modify[K, V](kv: TreeMap[K, V], k: K, f: Option[V] => V): TreeMap[K, V] = kv.updated(k, (f compose kv.get)(k))
  def modify[K, V](kv: TreeMap[K, V], k: K, init: => V, f: V => V): TreeMap[K, V] = modify(kv, k, (x: Option[V]) => x match {case Some(v) => f(v); case None => init})
  def init[K, V](kv: TreeMap[K, V], k: K, init: => V): TreeMap[K, V] = modify(kv, k, init, identity[V])

  def emptyOpenDoorsCountersProjection = Projection.build .
    addHandler(Door.tag, (d: Data, e: Database.EventData[Door.Event]) => {
      import Door._
      e.data match {
        case Registered(aggId) => Data(Some(e.id), d.doorCounters.updated(e.id, DoorState(TreeMap.empty)))
        case Closed => d.copy(nextDoor = None)
        case Opened => Data(Some(e.id), init(d.doorCounters, e.id, DoorState(TreeMap.empty)))
        case _ => d
      }}
    ).
    addHandler(Counter.tag, (d: Data, e: Database.EventData[Counter.Event]) => {
      import Counter._

      def updateDoorCounter(d: Data, doorId: AggregateId, counterId: AggregateId, init: => Int, update: Int => Int): Data =
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

      e.data match {
        case Incremented => {
          d.nextDoor match {
            case Some(doorId) => updateDoorCounter(d, doorId, e.id, 1, (c:Int) => c + 1)
            case _ => d
          }
        }
        case Decremented => {
          d.nextDoor match {
            case Some(doorId) => updateDoorCounter(d, doorId, e.id, -1, (c:Int) => c - 1)
            case _ => d
          }
        }
        case _ => d
      }}
    ) . empty(Data(None, new TreeMap()))
}



