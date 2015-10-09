package Cqrs

// import cats.data.Xor
//import cats.Monad
// import cats.arrow.NaturalTransformation
//import cats._
// import cats.free.Free
// import cats.state._
// import cats.free.Free.{pure, liftF}

// import cats.std.all._

import Cqrs.Aggregate._
import InMemoryDb._

class Projection {
// get db, find handler by E, loop through aggregates, find new unhandled events, loop, return new handler and projection's data
}


import scala.collection.immutable.TreeMap

import Domain.Counter

object CounterProjection {
  def empty = CounterProjection(new TreeMap(), CounterProjectionData(0))

  trait Handler[E] {
    def hashPrefix: String
    def handle(id: AggregateId, e: E, data: CounterProjectionData): CounterProjectionData
  }

  implicit object CounterHandler extends Handler[Counter.Event] {
    import Counter._
    def hashPrefix = "Counter_"
    def handle(id: AggregateId, e: Event, d: CounterProjectionData) = e match {
      case Created(id) => println ("created "+id) ; d
      case Incremented => println ("+1") ; d.copy(counter=d.counter+1)
      case Decremented => println ("-1") ; d.copy(counter=d.counter-1)
    }
  }

  def applyNewEventsFromDbToProjection[E: Handler](db: DbBackend[E], initialProjection: CounterProjection): CounterProjection = {
    val handler = implicitly[Handler[E]]
    val prefix = handler.hashPrefix
    println("====== vv =====.... " + handler.hashPrefix)

    def applyNewEventsToData(data: CounterProjectionData, aggregateId: AggregateId, events: TreeMap[Int, List[E]]) = {
      events.foldLeft(data)((d, el) => el._2.foldLeft(d)((d_, event) => handler.handle(aggregateId, event, d_)))
    }

    def applyNewAggregateEvents(proj: CounterProjection, aggregateId: AggregateId, events: TreeMap[Int, List[E]]) = {
      val aggregateHash = prefix + aggregateId
      val fromVersion = proj.readEvents.get(aggregateHash).fold(0)(_ + 1)
      val newEvents = events.from(fromVersion)
      val newData = applyNewEventsToData(proj.data, aggregateId, newEvents)
      val newReadEvents = proj.readEvents.updated(aggregateHash, newEvents.lastKey)
      CounterProjection(newReadEvents, newData)
    }

    db.foldLeft(initialProjection)((proj, farg) => applyNewAggregateEvents(proj, farg._1, farg._2))
  }

}

final case class CounterProjectionData(counter: Int) // todo: per aggregate
final case class CounterProjection(readEvents: TreeMap[String, Int], data: CounterProjectionData) {

  import CounterProjection._

  def applyNewEventsFromDb[E: Handler](db: DbBackend[E]): CounterProjection = {
    val r = applyNewEventsFromDbToProjection(db, this)
    println(r)
    r
  }
}
