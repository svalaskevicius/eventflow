package Cqrs

import cats.data.{Xor, XorT}
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{pure, liftF}

import cats.std.all._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap

// todo: add type for event processor monad
// todo: errors as string, no need for a list

  // class Projection[R] (on: EventRouter#EventReader, result: R)

object Aggregate {

  type AggregateId = String
  type Errors = List[String]

  case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseF[+Next]
  case class ReadAggregate[Next, E](id: AggregateId, fromVersion: Int, onEvents: List[VersionedEvents[E]] => Next) extends EventDatabaseF[Next]
  case class WriteAggregate[Next, E](id: AggregateId, events: VersionedEvents[E], cont: Next) extends EventDatabaseF[Next]

  implicit object EventDatabaseFunctor extends Functor[EventDatabaseF] {
    def map[A, B](fa: EventDatabaseF[A])(f: A => B): EventDatabaseF[B] = fa match {
      case c: ReadAggregate[A, t] => c.copy(onEvents = c.onEvents andThen f)
      case c: WriteAggregate[A, t] => c.copy(cont = f(c.cont))
    }
  }

  type EventDatabase[A] = Free[EventDatabaseF, A]
  type EventDatabaseWithFailure[A] = XorT[EventDatabase, Errors, A]

  type InMemoryDb[E] = SortedMap[AggregateId, SortedMap[Int, List[E]]]

  def newDb[E](): InMemoryDb[E] = new TreeMap()

  def readFromDb[E](database: InMemoryDb[E], id: AggregateId, fromVersion: Int): Errors Xor List[VersionedEvents[E]] = {
    database.get(id).fold[Errors Xor List[VersionedEvents[E]]](
      Xor.left(List("Cannot load aggregate '"+id+"'"))
    )(
      (evs: SortedMap[Int, List[E]]) => Xor.right(
        evs.from(fromVersion + 1).toList.map(v => VersionedEvents[E](v._1, v._2))
      )
    )
  }

  def addToDb[E](database: InMemoryDb[E], id: AggregateId, events: VersionedEvents[E]): Errors Xor InMemoryDb[E] = {
    val currentEvents = database.get(id)
    val currentVersion = currentEvents.fold(0)(e => if (e.isEmpty) 0 else e.lastKey)
    if (currentVersion != events.version - 1) {
      Xor.left(List("Unexpected version: have "+currentVersion+", want to write "+events.version))
    } else {
      Xor.right(
        database.updated(
          id,
          currentEvents.fold(
            (new TreeMap[Int, List[E]]()).asInstanceOf[SortedMap[Int, List[E]]]
          )(
            _.updated(events.version, events.events)
          )
        )
      )
    }
  }

  def runInMemoryDb_[A, E](database: InMemoryDb[E])(actions: EventDatabase[Errors Xor A]): Errors Xor A =
    actions.fold(
      identity,
      {
        case a: ReadAggregate[t, E] => {
          println("reading from DB: '" + a + "'... "+database)
            //          runInMemoryDb_(database)(a.onEvents(VersionedEvents[e](0, List())))
          val d = readFromDb[E](database, a.id, a.fromVersion)
          println("result: " + d)
          d >>= (evs => runInMemoryDb_[A, E](database)(a.onEvents(evs)))
        }
        case a: WriteAggregate[t, E] => {
          println("writing to DB: '" + a + "'... "+database)
          val d = addToDb(database, a.id, a.events)
          println("result: " + d)
          d >>= (db => runInMemoryDb_(db)(a.cont))
        }
      }
    )
  def runInMemoryDb[A, E](database: InMemoryDb[E])(actions: EventDatabaseWithFailure[A]): Errors Xor A =
    runInMemoryDb_(database)(actions.value)

  def readNewEvents[E](id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[List[VersionedEvents[E]]] =
    XorT.right[EventDatabase, Errors, List[VersionedEvents[E]]](
      liftF(ReadAggregate[List[VersionedEvents[E]], E](id, fromVersion, identity))
    )

  def writeEvents[E](id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[Unit] =
    XorT.right[EventDatabase, Errors, Unit](
      liftF(WriteAggregate(id, events, ()))
    )

  def pure[A](x: A): EventDatabaseWithFailure[A] = XorT.pure[EventDatabase, Errors, A](x)

  def emitEvent[E, Errors](ev: E): Errors Xor List[E] = Xor.right(List(ev))
  def emitEvents[E, Errors](evs: List[E]): Errors Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): List[String] Xor Events = Xor.left(List(err))
}

class Aggregate[E, C, D] (
                           id: String,
                           on: Aggregate[E, C, D]#EventHandler,
                           handle: Aggregate[E, C, D]#CommandHandler,
                           private[this] var state: D,
                           private[this] var version: Int = 0
                         ) {
  type Errors = List[String]
  type Events = List[E]
  type CommandHandler = C => D => Errors Xor Events
  type EventHandler = E => D => D

  import Aggregate._

  def initAggregate(): EventDatabaseWithFailure[Unit] = {
    //TODO: check that there were no events saved for it before!
    writeEvents(id, VersionedEvents[E](1, List()))
  }

  def handleCommand(cmd: C): EventDatabaseWithFailure[Unit] = {
    readNewEvents(id, version) >>=
      ((evs: List[VersionedEvents[E]]) => applyEvents(evs) >>
         handleCmd(cmd) >>=
         (evs => onEvents(evs)))
  }

  private def handleCmd(cmd: C): EventDatabaseWithFailure[Events] =
    XorT.fromXor[EventDatabase][Errors, Events](handle(cmd)(state))

  private def onEvents(evs: Events): EventDatabaseWithFailure[Unit] = {
    val vevs = VersionedEvents[E](version+1, evs)
    writeEvents(id, vevs) >> applyEvents(List(vevs))
  }

  private def applyEvents(evs: List[VersionedEvents[E]]): EventDatabaseWithFailure[Unit] = {
    println("Applying events on aggregate: " + evs)
    evs.map(ve => {
      version = ve.version
      state = ve.events.foldLeft(state)((d, e) => on(e)(d))
    })
    XorT.pure(())
  }
}

