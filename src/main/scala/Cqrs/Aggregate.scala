package Cqrs

import cats.data.{Xor, XorT}
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{pure, liftF}
import cats.state._

import cats.syntax.flatMap._

object Aggregate {

  type AggregateId = String
  val emptyAggregateId = ""

  trait Error
  final case class ErrorExistsAlready(id: AggregateId) extends Error
  final case class ErrorDoesNotExist(id: AggregateId) extends Error
  final case class ErrorUnexpectedVersion(id: AggregateId, currentVersion: Int, targetVersion: Int) extends Error
  final case class ErrorCommandFailure(message: String) extends Error

  trait Event
  final case class VersionedEvents(version: Int, events: List[Event])

  sealed trait EventDatabaseOp[A]
  final case class ReadAggregateExistance(id: AggregateId) extends EventDatabaseOp[Error Xor Boolean]
  final case class ReadAggregate(id: AggregateId, fromVersion: Int) extends EventDatabaseOp[Error Xor List[VersionedEvents]]
  final case class AppendAggregateEvents(id: AggregateId, events: VersionedEvents) extends EventDatabaseOp[Error Xor Unit]

  type EventDatabase[A] = Free[EventDatabaseOp, A]
  type EventDatabaseWithFailure[A] = XorT[EventDatabase, Error, A]

  final case class AggregateState[D](id: AggregateId, state: D, version: Int)
  type AggregateDef[D, A] = StateT[EventDatabaseWithFailure, AggregateState[D], A]

  def lift[A](a: EventDatabaseOp[Error Xor A]): EventDatabaseWithFailure[A] =
    XorT[EventDatabase, Error, A](liftF[EventDatabaseOp, Error Xor A](a))

  def doesAggregateExist(id: AggregateId): EventDatabaseWithFailure[Boolean] = lift(ReadAggregateExistance(id))

  def readNewEvents(id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[List[VersionedEvents]] =
      lift(ReadAggregate(id, fromVersion))

  def appendEvents(id: AggregateId, events: VersionedEvents): EventDatabaseWithFailure[Unit] =
      lift(AppendAggregateEvents(id, events))

  def pure[A](x: A): EventDatabaseWithFailure[A] = XorT.pure[EventDatabase, Error, A](x)

  def emitEvent(ev: Event): Error Xor List[Event] = Xor.right(List(ev))
  def emitEvents(evs: List[Event]): Error Xor List[Event] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))
}

final case class Aggregate[C, D] (
  on: Aggregate[C, D]#EventHandler,
  handle: Aggregate[C, D]#CommandHandler
) {
  import Aggregate._

  type ADStateRun[A] = AggregateState[D] => EventDatabaseWithFailure[(AggregateState[D], A)]
  type AD[A] = AggregateDef[D, A]
  def AD[A](a: ADStateRun[A]) : AD[A] = StateT[EventDatabaseWithFailure, AggregateState[D], A](a)

  type Events = List[Event]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = Event => D => D

  def liftToAggregateDef[A](f: EventDatabaseWithFailure[A]): AD[A] = AD(s => f.map((s, _)))

  def initAggregate(id: AggregateId): AD[Unit] =
    liftToAggregateDef (doesAggregateExist(id)) >>=
      ((e: Boolean) => AD(
         vs => {
           if (e) XorT.left[EventDatabase, Error, (AggregateState[D], Unit)](Free.pure(ErrorExistsAlready(id)))
           else appendEvents(id, VersionedEvents(1, List())).map(_ => (vs.copy(id = id), ()))
       }))

  def handleCommand(cmd: C): AD[Unit] = {
    for {
      events <- AD(vs => readNewEvents(vs.id, vs.version).map((vs, _)))
      _ <- applyEvents(events)
      resultEvents <- handleCmd(cmd)
      _ <- onEvents(resultEvents)
    } yield (())
  }

  private def handleCmd(cmd: C): AD[Events] = AD(vs =>
    XorT.fromXor[EventDatabase][Error, Events](handle(cmd)(vs.state)).map((vs, _))
  )

  private def onEvents(evs: Events): AD[Unit] =
    AD(vs => {
         val vevs = VersionedEvents(vs.version + 1, evs)
         appendEvents(vs.id, vevs).map(_ => (vs, List(vevs)))
       }) >>=
      (applyEvents _)

  private def applyEvents(evs: List[VersionedEvents]): AD[Unit] =
    AD(vs => {
         println("Applying events on aggregate: " + evs)
         val vs_ = evs.foldLeft(vs)((vs_, ve) => {
                                      if (vs_.version < ve.version) {
                                        vs_.copy(state = ve.events.foldLeft(vs_.state)((d, e) => on(e)(d)), version = ve.version)
                                      } else {
                                        vs_
                                      }
                                    })
         XorT.pure((vs_, ()))
       })
}

