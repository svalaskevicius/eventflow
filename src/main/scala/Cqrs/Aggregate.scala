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

  final case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseOp[A]
  final case class ReadAggregateExistance(id: AggregateId) extends EventDatabaseOp[Error Xor Boolean]
  final case class ReadAggregate[E](id: AggregateId, fromVersion: Int) extends EventDatabaseOp[Error Xor List[VersionedEvents[E]]]
  final case class AppendAggregateEvents[E](id: AggregateId, events: VersionedEvents[E]) extends EventDatabaseOp[Error Xor Unit]

  type EventDatabase[A] = Free[EventDatabaseOp, A]
  type EventDatabaseWithFailure[A] = XorT[EventDatabase, Error, A]

  final case class AggregateState[D](id: AggregateId, state: D, version: Int)
  type AggregateDef[D, A] = StateT[EventDatabaseWithFailure, AggregateState[D], A]


  def doesAggregateExist(id: AggregateId): EventDatabaseWithFailure[Boolean] =
    XorT[EventDatabase, Error, Boolean](
      liftF(ReadAggregateExistance(id))
    )

  def readNewEvents[E](id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[List[VersionedEvents[E]]] =
    XorT[EventDatabase, Error, List[VersionedEvents[E]]](
      liftF[EventDatabaseOp, Error Xor List[VersionedEvents[E]]](ReadAggregate[E](id, fromVersion))
    )

  def appendEvents[E](id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[Unit] =
    XorT[EventDatabase, Error, Unit](
      liftF[EventDatabaseOp, Error Xor Unit](AppendAggregateEvents(id, events))
    )

  def pure[A](x: A): EventDatabaseWithFailure[A] = XorT.pure[EventDatabase, Error, A](x)

  def emitEvent[E](ev: E): Error Xor List[E] = Xor.right(List(ev))
  def emitEvents[E](evs: List[E]): Error Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))
}

final case class Aggregate[E, C, D] (
  on: Aggregate[E, C, D]#EventHandler,
  handle: Aggregate[E, C, D]#CommandHandler
) {
  import Aggregate._

  type ADStateRun[A] = AggregateState[D] => EventDatabaseWithFailure[(AggregateState[D], A)]
  type AD[A] = AggregateDef[D, A]
  def AD[A](a: ADStateRun[A]) : AD[A] = StateT[EventDatabaseWithFailure, AggregateState[D], A](a)

  type Events = List[E]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = E => D => D

  def liftToAggregateDef[A](f: EventDatabaseWithFailure[A]): AD[A] =
    StateT[EventDatabaseWithFailure, AggregateState[D], A](s => f.map((s, _)))

  def initAggregate(id: AggregateId): AD[Unit] =
    liftToAggregateDef (doesAggregateExist(id)) >>=
      ((e: Boolean) => StateT[EventDatabaseWithFailure, AggregateState[D], Unit](
         vs => {
           if (e) XorT.left[EventDatabase, Error, (AggregateState[D], Unit)](Free.pure(ErrorExistsAlready(id)))
           else appendEvents(id, VersionedEvents[E](1, List())).map(_ => (vs.copy(id = id), ()))
       }))

  def handleCommand(cmd: C): AD[Unit] = {
    for {
      events <- AD(vs => readNewEvents[E](vs.id, vs.version).map((vs, _)))
      _ <- applyEvents(events)
      resultEvents <- handleCmd(cmd)
      _ <- onEvents(resultEvents)
    } yield (())
  }

  private def handleCmd(cmd: C): AD[Events] = StateT[EventDatabaseWithFailure, AggregateState[D], Events](vs =>
    XorT.fromXor[EventDatabase][Error, Events](handle(cmd)(vs.state)).map((vs, _))
  )

  private def onEvents(evs: Events): AD[Unit] =
    StateT[EventDatabaseWithFailure, AggregateState[D], List[VersionedEvents[E]]](
      vs => {
        val vevs = VersionedEvents[E](vs.version+1, evs)
        appendEvents(vs.id, vevs).map(_ => (vs, List(vevs)))
      }) >>=
      (applyEvents _)

  private def applyEvents(evs: List[VersionedEvents[E]]): AD[Unit] =
    StateT[EventDatabaseWithFailure, AggregateState[D], Unit](
      vs => {
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

