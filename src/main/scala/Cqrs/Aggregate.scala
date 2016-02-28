package Cqrs

import Cqrs.Aggregate.AggregateId
import Cqrs.Database.{EventDatabaseWithFailure, VersionedEvents}
import cats.{MonadState, MonadError}
import cats.data.{Xor, XorT}
import cats.state._

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


  type DatabaseWithAnyFailure[E, Err, A] = XorT[EventDatabaseWithFailure[E, ?], Err, A]
  type DatabaseWithAggregateFailure[E, A] = DatabaseWithAnyFailure[E, Error, A]

  def dbAction[E, A](dbActions: Database.EventDatabaseWithFailure[E, A]): DatabaseWithAggregateFailure[E, A] =
    XorT[EventDatabaseWithFailure[E, ?], Error, A](dbActions.map(Xor.right))

  final case class AggregateState[D](id: AggregateId, state: D, version: Int)
  type AggregateDefAnyD[E, D, A] = StateT[DatabaseWithAggregateFailure[E, ?], D, A]
  type AggregateDef[E, D, A] = AggregateDefAnyD[E, AggregateState[D], A]

  implicit def eventDatabaseWithFailureMonad[E]: MonadError[DatabaseWithAnyFailure[E, ?, ?], Error] = XorT.xorTMonadError[EventDatabaseWithFailure[E, ?], Error]
  implicit def aggregateDefMonad[E, D]: MonadState[AggregateDefAnyD[E, ?, ?], AggregateState[D]] = StateT.stateTMonadState[DatabaseWithAggregateFailure[E, ?], AggregateState[D]]

  def pure[E, A](x: A): DatabaseWithAggregateFailure[E, A] = eventDatabaseWithFailureMonad[E].pure(x)
  def fail[E, A](x: Error): DatabaseWithAggregateFailure[E, A] = eventDatabaseWithFailureMonad[E].raiseError[A](x)

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
  type ADStateRun[A] = AggregateState[D] => DatabaseWithAggregateFailure[E, (AggregateState[D], A)]

  type AggregateDefinition[A] = AggregateDef[E, D, A]
  def defineAggregate[A](a: ADStateRun[A]): AggregateDefinition[A] = StateT[DatabaseWithAggregateFailure[E, ?], AggregateState[D], A](a)

  type Events = List[E]
  type CommandHandler = C => D => Aggregate.Error Xor Events
  type EventHandler = E => D => D

  def liftToAggregateDef[A](f: DatabaseWithAggregateFailure[E, A]): AggregateDefinition[A] = defineAggregate(s => f.map((s, _)))

  def initAggregate[Cmd <: InitialAggregateCommand with C](initCmd: Cmd): DatabaseWithAggregateFailure[E, State] = {
    import Database._
    val id = initCmd.id
    val initState: DatabaseWithAggregateFailure[E, State] =
      dbAction(doesAggregateExist[E](tag, id)).flatMap { e: Boolean =>
        if (e) fail(ErrorExistsAlready(id))
        else dbAction(appendEvents[E](tag, id, VersionedEvents[E](1, List())).map(_ => newState(id)))
      }
    initState.flatMap(handleCommand(initCmd).runS)
  }

  def newState(id: AggregateId) = new State(id, initData, 0)

  def handleCommand(cmd: C): AggregateDefinition[Unit] = {
    import Database._
    for {
      events <- defineAggregate(vs => dbAction(readNewEvents[E](tag, vs.id, vs.version).map((vs, _))))
      _ <- applyEvents(events)
      resultEvents <- handleCmd(cmd)
      _ <- onEvents(resultEvents)
    } yield ()
  }

  private def handleCmd(cmd: C): AggregateDefinition[Events] = defineAggregate(vs =>
    XorT.fromXor[EventDatabaseWithFailure[E, ?]](
      handle(cmd)(vs.state)
    ).map((vs, _))
  )

  private def onEvents(evs: Events): AggregateDefinition[Unit] =
    defineAggregate { vs =>
      import Database._
      val vevs = VersionedEvents[E](vs.version + 1, evs)
      dbAction(appendEvents(tag, vs.id, vevs).map(_ => (vs, List(vevs))))
    }.flatMap(applyEvents)

  private def applyEvents(evs: List[VersionedEvents[E]]): AggregateDefinition[Unit] =
    defineAggregate { vs =>
      val vs_ = evs.foldLeft(vs)((vs_, ve) => {
        if (vs_.version < ve.version) {
          vs_.copy(state = ve.events.foldLeft(vs_.state)((d, e) => on(e)(d)), version = ve.version)
        } else {
          vs_
        }
      })
      pure((vs_, ()))
    }
}

