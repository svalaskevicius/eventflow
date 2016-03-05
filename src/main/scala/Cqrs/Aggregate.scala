package Cqrs

import Cqrs.Aggregate.AggregateId
import Cqrs.Database.{ EventDatabaseWithFailure, VersionedEvents }
import algebra.Semigroup
import cats.data.{ NonEmptyList => NEL, Validated, ValidatedNel, Xor, XorT }
import cats.state._
import cats.std.all._
import cats.{ MonadError, MonadState, SemigroupK }

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
  final case class Errors(err: NEL[Error]) extends Error

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

  type CommandHandlerResult[E] = ValidatedNel[Aggregate.Error, List[E]]
  def emitEvent[E](ev: E): CommandHandlerResult[E] = Validated.valid(List(ev))
  def emitEvents[E](evs: List[E]): CommandHandlerResult[E] = Validated.valid(evs)

  implicit val nelErrorSemigroup: Semigroup[NEL[Error]] = SemigroupK[NEL].algebra[Error]

  def failCommand[E](err: String): CommandHandlerResult[E] = Validated.invalid(NEL(ErrorCommandFailure(err)))

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
  def liftAggregate[A](a: DatabaseWithAggregateFailure[E, A]): AggregateDefinition[A] = defineAggregate[A](s => a.map(ret => (s, ret)))

  type CommandHandler = C => D => CommandHandlerResult[E]
  type EventHandler = E => D => D

  def liftToAggregateDef[A](f: DatabaseWithAggregateFailure[E, A]): AggregateDefinition[A] = defineAggregate(s => f.map((s, _)))

  def newState(id: AggregateId) = new State(id, initData, 0)

  def handleCommand(cmd: C): AggregateDefinition[Unit] = {
    import Database._

    val catchUpEvents = for {
      events <- defineAggregate(vs => dbAction(readNewEvents[E](tag, vs.id, vs.version).map((vs, _))))
      _ <- applyEvents(events)
    } yield ()

    def initAggregateInDb(id: AggregateId): AggregateDefinition[Unit] =
      liftAggregate[Unit] {
        dbAction[E, Boolean](doesAggregateExist[E](tag, id)).flatMap { e: Boolean =>
          if (e) fail(ErrorExistsAlready(id))
          else pure(())
        }
      }

    val initActions: AggregateDefinition[Unit] = cmd match {
      case initCmd: InitialAggregateCommand => initAggregateInDb(initCmd.id)
      case _ => catchUpEvents
    }

    for {
      _ <- initActions
      resultEvents <- handleCmd(cmd)
      _ <- onEvents(resultEvents)
    } yield ()
  }

  def loadAndHandleCommand(id: AggregateId, cmd: C): DatabaseWithAggregateFailure[E, State] =
    handleCommand(cmd).runS(newState(id))

  private def handleCmd(cmd: C): AggregateDefinition[List[E]] = defineAggregate(vs =>
    XorT.fromXor[EventDatabaseWithFailure[E, ?]](
      handle(cmd)(vs.state).fold[Error Xor List[E]](err => Xor.left(Errors(err)), Xor.right)
    ).map((vs, _)))

  private def onEvents(evs: List[E]): AggregateDefinition[Unit] =
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

