package Domain

import Cqrs.DbAdapters.InMemoryDb._
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

  def emptyOpenDoorsCountersProjection = Projection.empty[Data](Data(None, new TreeMap()), List(
    (Door.tag, createEventDataConsumer( (d: Data, t: Tag, id: AggregateId, v: Int, e: Door.Event) => {
      import Door._
      println("ZZ>1> "+e)
      e match {
        case Registered(id) => Data(Some(id), d.doorCounters.updated(id, DoorState(TreeMap.empty)))
        case Closed => println ("closed") ; d.copy(nextDoor = None)
        case Opened => println ("opened") ; Data(Some(id), init(d.doorCounters, id, DoorState(TreeMap.empty)))
        case _ => d
      }}
    )),
    (Counter.tag, createEventDataConsumer( (d: Data, t: Tag, id: AggregateId, v: Int, e: Counter.Event) => {
      import Counter._

      println("ZZ>2> "+e)
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

      e match {
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
      }}
    ))
  ))
}



