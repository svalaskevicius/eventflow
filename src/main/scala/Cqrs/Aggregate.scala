package Cqrs

import cats.data.{Xor, XorT}
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{pure, liftF}

import cats.std.all._
import cats.syntax.flatMap._


// todo: add type for event processor monad
// todo: errors as string, no need for a list

class Projection[R] (on: EventRouter#EventReader, result: R)

object Aggregate {

  type AggregateId = String

  final case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseF[+Next]
  case class ReadAggregate[Next, Evts](id: AggregateId, fromVersion: Int, onEvents: VersionedEvents[Evts] => Next) extends EventDatabaseF[Next]
  case class WriteAggregate[Next, Evts](id: AggregateId, events: VersionedEvents[Evts], cont: Next) extends EventDatabaseF[Next]

  implicit object EventDatabaseFunctor extends Functor[EventDatabaseF] {
    def map[A, B](fa: EventDatabaseF[A])(f: A => B): EventDatabaseF[B] = fa match {
      case c: ReadAggregate[A, t] => c.copy(onEvents = c.onEvents andThen f)
      case c: WriteAggregate[A, t] => c.copy(cont = f(c.cont))
    }
  }

  type EventDatabase[A] = Free[EventDatabaseF, A]

  def readEvents[E](id: AggregateId, fromVersion: Int): EventDatabase[VersionedEvents[E]] = liftF(ReadAggregate[VersionedEvents[E], E](id, fromVersion, identity))
  def writeEvents[E](id: AggregateId, events: VersionedEvents[E]): EventDatabase[Unit] = liftF(WriteAggregate(id, events, ()))

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

  def handleCommand(cmd: C): XorT[EventDatabase, Errors, Unit] = {
    // OMG MAKE THIS READABLE!
    XorT.right[EventDatabase, Errors, VersionedEvents[E]](readEvents[E](id, version)) >>=
      ((evs: VersionedEvents[E]) => XorT.right[EventDatabase, Errors, Unit](applyEvents(evs)) >>
         ((XorT.fromXor[EventDatabase][Errors, List[E]](handle(cmd)(state))) >>=
         (evs => XorT.right(onEvents(evs)))))
  }

  private def onEvents(evs: Events): EventDatabase[Unit] = {
    val vevs = VersionedEvents(version, evs)
    writeEvents(id, vevs) >> applyEvents(vevs)
  }

  private def applyEvents(evs: VersionedEvents[E]): EventDatabase[Unit] = {
    println(evs)
    version = evs.version
    state = evs.events.foldLeft(state)((d, e) => on(e)(d))
    pure(())
  }
}

