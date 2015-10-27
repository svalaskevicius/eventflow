package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }

import scala.collection.immutable.TreeMap

object OpenDoorsCountersProjection {

  final case class DoorState(counters: TreeMap[AggregateId, Int])

  final case class Data(nextDoor: Option[AggregateId], doorCounters: TreeMap[AggregateId, DoorState])

  def emptyOpenDoorsCountersProjection = Projection.empty[Data](Data(None, new TreeMap()))

  def modify[K, V](kv: TreeMap[K, V], k: K, f: Option[V] => V): TreeMap[K, V] = kv.updated(k, (f compose kv.get)(k))
  def modify[K, V](kv: TreeMap[K, V], k: K, init: => V, f: V => V): TreeMap[K, V] = modify(kv, k, (x: Option[V]) => x match {case Some(v) => f(v); case None => init})
  def init[K, V](kv: TreeMap[K, V], k: K, init: => V): TreeMap[K, V] = modify(kv, k, init, identity[V])

  implicit object DoorHandler extends Projection.Handler[Door.Event, Data] {

    import Door._

    def hashPrefix = "Door"

    def handle(id: AggregateId, e: Event, d: Data) = e match {
      case Registered(id) => Data(Some(id), d.doorCounters.updated(id, DoorState(TreeMap.empty)))
      case Closed => println ("closed") ; d.copy(nextDoor = None)
      case Opened => println ("opened") ; Data(Some(id), init(d.doorCounters, id, DoorState(TreeMap.empty)))
      case _ => d
    }
  }

  implicit object CounterHandler extends Projection.Handler[Counter.Event, Data] {

    import Counter._

    def hashPrefix = "Counter"

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

    def handle(id: AggregateId, e: Event, d: Data) = e match {
      case Incremented => println ("inc") ; {
        d.nextDoor match {
          case Some(doorId) => updateDoorCounter(d, doorId, id, 1, (c:Int) => c + 1)
          case _ => d
        }
      }
      case Decremented => println ("dec") ; {
        d.nextDoor match {
          case Some(doorId) => updateDoorCounter(d, doorId, id, -1, (c:Int) => c - 1)
          case _ => d
        }
      }
      case _ => d
    }
  }
}



