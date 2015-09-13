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

// class Projection[R] (on: EventRouter#EventReader, result: R)

object Aggregate {

  type AggregateId = String

  trait Error
  case class ErrorExistsAlready(id: AggregateId) extends Error
  case class ErrorDoesNotExist(id: AggregateId) extends Error
  case class ErrorUnexpectedVersion(id: AggregateId, currentVersion: Int, targetVersion: Int) extends Error
  case class ErrorCommandFailure(message: String) extends Error

  case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseF[+Next]
  case class ReadAggregateExistance[Next](id: AggregateId, cont: Boolean => Next) extends EventDatabaseF[Next]
  case class ReadAggregate[Next, E](id: AggregateId, fromVersion: Int, onEvents: List[VersionedEvents[E]] => Next) extends EventDatabaseF[Next]
  case class WriteAggregate[Next, E](id: AggregateId, events: VersionedEvents[E], cont: Next) extends EventDatabaseF[Next]

  implicit object EventDatabaseFunctor extends Functor[EventDatabaseF] {
    def map[A, B](fa: EventDatabaseF[A])(f: A => B): EventDatabaseF[B] = fa match {
      case c: ReadAggregateExistance[A] => c.copy(cont = c.cont andThen f)
      case c: ReadAggregate[A, t] => c.copy(onEvents = c.onEvents andThen f)
      case c: WriteAggregate[A, t] => c.copy(cont = f(c.cont))
    }
  }

  type EventDatabase[A] = Free[EventDatabaseF, A]
  type EventDatabaseWithFailure[A] = XorT[EventDatabase, Error, A]

  def doesAggregateExist(id: AggregateId): EventDatabaseWithFailure[Boolean] =
    XorT.right[EventDatabase, Error, Boolean](
      liftF(ReadAggregateExistance[Boolean](id, identity))
    )

  def readNewEvents[E](id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[List[VersionedEvents[E]]] =
    XorT.right[EventDatabase, Error, List[VersionedEvents[E]]](
      liftF(ReadAggregate[List[VersionedEvents[E]], E](id, fromVersion, identity))
    )

  def writeEvents[E](id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[Unit] =
    XorT.right[EventDatabase, Error, Unit](
      liftF(WriteAggregate(id, events, ()))
    )

  def pure[A](x: A): EventDatabaseWithFailure[A] = XorT.pure[EventDatabase, Error, A](x)

  def emitEvent[E, Error](ev: E): Error Xor List[E] = Xor.right(List(ev))
  def emitEvents[E, Error](evs: List[E]): Error Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))
}

class Aggregate[E, C, D] (
  id: String,
  on: Aggregate[E, C, D]#EventHandler,
  handle: Aggregate[E, C, D]#CommandHandler,
  private[this] var state: D,
  private[this] var version: Int = 0
) {
  type Events = List[E]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = E => D => D

  import Aggregate._

  def initAggregate(): EventDatabaseWithFailure[Unit] = {
    doesAggregateExist(id) >>=
      ((e: Boolean) => if (e) XorT.left[EventDatabase, Error, Unit](Free.pure(ErrorExistsAlready(id)))
                       else writeEvents(id, VersionedEvents[E](1, List())))
  }

  def handleCommand(cmd: C): EventDatabaseWithFailure[Unit] = {
    readNewEvents(id, version) >>=
      ((evs: List[VersionedEvents[E]]) => applyEvents(evs) >>
         handleCmd(cmd) >>=
         (evs => onEvents(evs)))
  }

  private def handleCmd(cmd: C): EventDatabaseWithFailure[Events] =
    XorT.fromXor[EventDatabase][Error, Events](handle(cmd)(state))

  private def onEvents(evs: Events): EventDatabaseWithFailure[Unit] = {
    val vevs = VersionedEvents[E](version+1, evs)
    writeEvents(id, vevs) >> applyEvents(List(vevs))
  }

  private def applyEvents(evs: List[VersionedEvents[E]]): EventDatabaseWithFailure[Unit] = {
    println("Applying events on aggregate: " + evs)
    evs.map(ve => {
              if (version < ve.version) {
                version = ve.version
                state = ve.events.foldLeft(state)((d, e) => on(e)(d))
              }
            })
    XorT.pure(())
  }
}

