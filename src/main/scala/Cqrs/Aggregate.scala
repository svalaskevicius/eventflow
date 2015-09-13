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
  final case class ErrorExistsAlready(id: AggregateId) extends Error
  final case class ErrorDoesNotExist(id: AggregateId) extends Error
  final case class ErrorUnexpectedVersion(id: AggregateId, currentVersion: Int, targetVersion: Int) extends Error
  final case class ErrorCommandFailure(message: String) extends Error

  final case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseOp[A]
  final case class ReadAggregateExistance(id: AggregateId) extends EventDatabaseOp[Error Xor Boolean]
  final case class ReadAggregate[E](id: AggregateId, fromVersion: Int) extends EventDatabaseOp[Error Xor List[VersionedEvents[E]]]
  final case class WriteAggregate[E](id: AggregateId, events: VersionedEvents[E]) extends EventDatabaseOp[Error Xor Unit]

  type EventDatabase[A] = Free[EventDatabaseOp, A]
  type EventDatabaseWithFailure[A] = XorT[EventDatabase, Error, A]

  def doesAggregateExist(id: AggregateId): EventDatabaseWithFailure[Boolean] =
    XorT[EventDatabase, Error, Boolean](
      liftF(ReadAggregateExistance(id))
    )

  def readNewEvents[E](id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[List[VersionedEvents[E]]] =
    XorT[EventDatabase, Error, List[VersionedEvents[E]]](
      liftF[EventDatabaseOp, Error Xor List[VersionedEvents[E]]](ReadAggregate[E](id, fromVersion))
    )

  def writeEvents[E](id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[Unit] =
    XorT[EventDatabase, Error, Unit](
      liftF[EventDatabaseOp, Error Xor Unit](WriteAggregate(id, events))
    )

  def pure[A](x: A): EventDatabaseWithFailure[A] = XorT.pure[EventDatabase, Error, A](x)

  def emitEvent[E](ev: E): Error Xor List[E] = Xor.right(List(ev))
  def emitEvents[E](evs: List[E]): Error Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))
}

final case class Aggregate[E, C, D] (
  id: String,
  on: Aggregate[E, C, D]#EventHandler,
  handle: Aggregate[E, C, D]#CommandHandler,
  val state: D,
  val version: Int
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

  def handleCommand(cmd: C): EventDatabaseWithFailure[Aggregate[E, C, D]] = {
    (((readNewEvents(id, version) >>=
         applyEvents((version, state)) _) >>=
        (vs => (handleCmd(cmd)(vs) >>=
                  (onEvents(vs) _)))))
     }

  private def handleCmd(cmd: C)(vs: (Int, D)): EventDatabaseWithFailure[Events] =
    XorT.fromXor[EventDatabase][Error, Events](handle(cmd)(vs._2))

  private def onEvents(vs: (Int, D))(evs: Events): EventDatabaseWithFailure[Aggregate[E, C, D]] = {
    val vevs = VersionedEvents[E](vs._1+1, evs)
    writeEvents(id, vevs) >>
      applyEvents(vs)(List(vevs)) >>=
      (s => XorT.pure(this.copy(version = s._1, state = s._2)))
  }

  private def applyEvents(vs: (Int, D))(evs: List[VersionedEvents[E]]): EventDatabaseWithFailure[(Int, D)] = {
    println("Applying events on aggregate: " + evs)
    val vs_ = evs.foldLeft(vs)((vs_, ve) => {
              if (vs_._1 < ve.version) {
                (ve.version, ve.events.foldLeft(vs_._2)((d, e) => on(e)(d)))
              } else {
                vs_
              }
            })
    XorT.pure(vs_)
  }
}

