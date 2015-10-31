package Cqrs

import cats.data.{ Xor, XorT }
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{ pure, liftF }
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

  sealed trait EventDatabaseOp[E, A]
  final case class ReadAggregateExistence[E](tag: String, id: AggregateId) extends EventDatabaseOp[E, Error Xor Boolean]
  final case class ReadAggregate[E](tag: String, id: AggregateId, fromVersion: Int) extends EventDatabaseOp[E, Error Xor List[VersionedEvents[E]]]
  final case class AppendAggregateEvents[E](tag: String, id: AggregateId, events: VersionedEvents[E]) extends EventDatabaseOp[E, Error Xor Unit]

  type EventDatabase[E, A] = Free[EventDatabaseOp[E, ?], A]
  type EventDatabaseWithAnyFailure[E, Err, A] = XorT[EventDatabase[E, ?], Err, A]
  type EventDatabaseWithFailure[E, A] = EventDatabaseWithAnyFailure[E, Error, A]

  final case class AggregateState[D](id: AggregateId, state: D, version: Int)
  type AggregateDefAnyD[E, D, A] = StateT[EventDatabaseWithFailure[E, ?], D, A]
  type AggregateDef[E, D, A] = AggregateDefAnyD[E, AggregateState[D], A]

  def lift[E, A](a: EventDatabaseOp[E, Error Xor A]): EventDatabaseWithFailure[E, A] =
    XorT[EventDatabase[E, ?], Error, A](liftF[EventDatabaseOp[E, ?], Error Xor A](a))

  def doesAggregateExist[E](tag: String, id: AggregateId): EventDatabaseWithFailure[E, Boolean] = lift(ReadAggregateExistence[E](tag, id))

  def readNewEvents[E](tag: String, id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[E, List[VersionedEvents[E]]] =
    lift(ReadAggregate[E](tag, id, fromVersion))

  def appendEvents[E](tag: String, id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[E, Unit] =
    lift(AppendAggregateEvents(tag, id, events))

  implicit def eventDatabaseMonad[E]: Monad[EventDatabase[E, ?]] = Free.freeMonad[EventDatabaseOp[E, ?]]
  implicit def eventDatabaseWithFailureMonad[E]: MonadError[EventDatabaseWithAnyFailure[E, ?, ?], Error] = XorT.xorTMonadError[EventDatabase[E, ?], Error]
  implicit def aggregateDefMonad[E, D]: MonadState[AggregateDefAnyD[E, ?, ?], AggregateState[D]] = StateT.stateTMonadState[EventDatabaseWithFailure[E, ?], AggregateState[D]]

  def pure[E, A](x: A): EventDatabaseWithFailure[E, A] = eventDatabaseWithFailureMonad[E].pure(x)

  def emitEvent[E](ev: E): Error Xor List[E] = Xor.right(List(ev))
  def emitEvents[E](evs: List[E]): Error Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))

  type StatefulEventDatabaseWithFailure[E, D, A] = EventDatabaseWithFailure[E, (AggregateState[D], A)]

  def runAggregateFromStart[E, D, A](a: AggregateDef[E, D, A], initState: D): StatefulEventDatabaseWithFailure[E, D, A] =
    a.run(AggregateState(emptyAggregateId, initState, 0))

  def continueAggregate[E, D, A](a: AggregateDef[E, D, A], state: AggregateState[D]): StatefulEventDatabaseWithFailure[E, D, A] =
    a.run(state)

}

final case class Aggregate[E, C, D](
  on: Aggregate[E, C, D]#EventHandler,
  handle: Aggregate[E, C, D]#CommandHandler,
  tag: String
) {
  import Aggregate._

  type State = AggregateState[D]
  type ADStateRun[A] = AggregateState[D] => EventDatabaseWithFailure[E, (AggregateState[D], A)]
  type AD[A] = AggregateDef[E, D, A]
  def AD[A](a: ADStateRun[A]): AD[A] = StateT[EventDatabaseWithFailure[E, ?], AggregateState[D], A](a)

  type Events = List[E]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = E => D => D

  def liftToAggregateDef[A](f: EventDatabaseWithFailure[E, A]): AD[A] = AD(s => f.map((s, _)))

  def initAggregate(id: AggregateId): AD[Unit] =
    liftToAggregateDef(doesAggregateExist(tag, id)) >>=
      ((e: Boolean) => AD(
        vs => {
          if (e) XorT.left[EventDatabase[E, ?], Error, (AggregateState[D], Unit)](eventDatabaseMonad[E].pure(ErrorExistsAlready(id)))
          else appendEvents(tag, id, VersionedEvents[E](1, List())).map(_ => (vs.copy(id = id), ()))
        }
      ))

  def handleCommand(cmd: C): AD[Unit] = {
    for {
      events <- AD(vs => readNewEvents[E](tag, vs.id, vs.version).map((vs, _)))
      _ <- applyEvents(events)
      resultEvents <- handleCmd(cmd)
      _ <- onEvents(resultEvents)
    } yield ()
  }

  private def handleCmd(cmd: C): AD[Events] = AD(vs =>
    XorT.fromXor[EventDatabase[E, ?]][Error, Events](handle(cmd)(vs.state)).map((vs, _)))

  private def onEvents(evs: Events): AD[Unit] =
    AD(vs => {
      val vevs = VersionedEvents[E](vs.version + 1, evs)
      appendEvents(tag, vs.id, vevs).map(_ => (vs, List(vevs)))
    }) >>=
      applyEvents _

  private def applyEvents(evs: List[VersionedEvents[E]]): AD[Unit] =
    AD(vs => {
      println("Applying events on aggregate: " + evs)
      val vs_ = evs.foldLeft(vs)((vs_, ve) => {
        if (vs_.version < ve.version) {
          vs_.copy(state = ve.events.foldLeft(vs_.state)((d, e) => on(e)(d)), version = ve.version)
        } else {
          vs_
        }
      })
      eventDatabaseWithFailureMonad[E].pure((vs_, ()))
    })
}

