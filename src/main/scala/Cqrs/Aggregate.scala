package Cqrs

import Cqrs.Aggregate.AggregateId
import cats.data.{ Xor, XorT }
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{ pure, liftF }
import cats.state._

import cats.syntax.flatMap._

import scala.language.implicitConversions

object Aggregate {

  final case class Tag(v: String)
  final case class AggregateId(v: String)
  val emptyAggregateId = AggregateId("")
  implicit def toAggregateId(v: String): AggregateId = AggregateId(v)

  implicit def aggOrdering(implicit ev: Ordering[String]): Ordering[AggregateId] = new Ordering[AggregateId] {
    def compare(a: AggregateId, b: AggregateId) = ev.compare(a.v, b.v)
  }

  sealed trait Error
  final case class ErrorExistsAlready(id: AggregateId) extends Error
  final case class ErrorCommandFailure(message: String) extends Error
  final case class DatabaseError(err: Database.Error) extends Error
  case object ErrorCannotFindHandler extends Error


  final case class VersionedEvents[E](version: Int, events: List[E])

  sealed trait EventDatabaseOp[E, A]
  final case class ReadAggregateExistence[E](tag: Tag, id: AggregateId) extends EventDatabaseOp[E, Error Xor Boolean]
  final case class ReadAggregate[E](tag: Tag, id: AggregateId, fromVersion: Int) extends EventDatabaseOp[E, Error Xor List[VersionedEvents[E]]]
  final case class AppendAggregateEvents[E](tag: Tag, id: AggregateId, events: VersionedEvents[E]) extends EventDatabaseOp[E, Error Xor Unit]

  type EventDatabase[E, A] = Free[EventDatabaseOp[E, ?], A]
  type EventDatabaseWithAnyFailure[E, Err, A] = XorT[EventDatabase[E, ?], Err, A]
  type EventDatabaseWithFailure[E, A] = EventDatabaseWithAnyFailure[E, Error, A]

  final case class AggregateState[D](id: AggregateId, state: D, version: Int)
  type AggregateDefAnyD[E, D, A] = StateT[EventDatabaseWithFailure[E, ?], D, A]
  type AggregateDef[E, D, A] = AggregateDefAnyD[E, AggregateState[D], A]

  def lift[E, A](a: EventDatabaseOp[E, Error Xor A]): EventDatabaseWithFailure[E, A] =
    XorT[EventDatabase[E, ?], Error, A](liftF[EventDatabaseOp[E, ?], Error Xor A](a))

  def doesAggregateExist[E](tag: Tag, id: AggregateId): EventDatabaseWithFailure[E, Boolean] = lift(ReadAggregateExistence[E](tag, id))

  def readNewEvents[E](tag: Tag, id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[E, List[VersionedEvents[E]]] =
    lift(ReadAggregate[E](tag, id, fromVersion))

  def appendEvents[E](tag: Tag, id: AggregateId, events: VersionedEvents[E]): EventDatabaseWithFailure[E, Unit] =
    lift(AppendAggregateEvents(tag, id, events))

  implicit def eventDatabaseMonad[E]: Monad[EventDatabase[E, ?]] = Free.freeMonad[EventDatabaseOp[E, ?]]
  implicit def eventDatabaseWithFailureMonad[E]: MonadError[EventDatabaseWithAnyFailure[E, ?, ?], Error] = XorT.xorTMonadError[EventDatabase[E, ?], Error]
  implicit def aggregateDefMonad[E, D]: MonadState[AggregateDefAnyD[E, ?, ?], AggregateState[D]] = StateT.stateTMonadState[EventDatabaseWithFailure[E, ?], AggregateState[D]]

  def pure[E, A](x: A): EventDatabaseWithFailure[E, A] = eventDatabaseWithFailureMonad[E].pure(x)

  def emitEvent[E](ev: E): Error Xor List[E] = Xor.right(List(ev))
  def emitEvents[E](evs: List[E]): Error Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): Error Xor Events = Xor.left(ErrorCommandFailure(err))
}

trait InitialAggregateCommand {
  def id: AggregateId
}

trait Aggregate[E, C, D] {

  import Aggregate._

  def tag: Aggregate.Tag

  protected def on: EventHandler
  protected def handle: CommandHandler
  protected def initData: D

  type State = AggregateState[D]
  type ADStateRun[A] = AggregateState[D] => EventDatabaseWithFailure[E, (AggregateState[D], A)]
  type AD[A] = AggregateDef[E, D, A]
  def AD[A](a: ADStateRun[A]): AD[A] = StateT[EventDatabaseWithFailure[E, ?], AggregateState[D], A](a)

  type Events = List[E]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = E => D => D

  def liftToAggregateDef[A](f: EventDatabaseWithFailure[E, A]): AD[A] = AD(s => f.map((s, _)))

  def initAggregate[Cmd <: InitialAggregateCommand with C](initCmd: Cmd): EventDatabaseWithFailure[E, State] = {
    val id = initCmd.id
    val initState = doesAggregateExist(tag, id).flatMap((e: Boolean) =>
      if (e) XorT.left[EventDatabase[E, ?], Error, AggregateState[D]](eventDatabaseMonad[E].pure(ErrorExistsAlready(id)))
      else appendEvents(tag, id, VersionedEvents[E](1, List())).map(_ => newState(id))
    )
    initState.flatMap(handleCommand(initCmd).runS)
  }

  def newState(id: AggregateId) = new State(id, initData, 0)

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

