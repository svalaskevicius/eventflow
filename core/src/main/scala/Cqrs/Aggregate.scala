package Cqrs

import Cqrs.Database.{ ErrorUnexpectedVersion, EventDatabaseWithFailure, Serializable }
import cats.kernel.Semigroup
import cats.data.{ EitherT, NonEmptyList => NEL, _ }
import cats.{ MonadError, MonadState, SemigroupK }

object Aggregate {

  trait EventTag {
    type Event

    def name: String

    def eventSerialiser: Serializable[Event]
  }

  type EventTagAux[E] = EventTag { type Event = E }

  def createTag[E](id: String)(implicit evSerialiser: Serializable[E]) = new EventTag {
    type Event = E
    val name = id
    val eventSerialiser = evSerialiser
  }

  type AggregateId = String

  val emptyAggregateId = ""

  implicit def aggOrdering(implicit ev: Ordering[String]): Ordering[AggregateId] = new Ordering[AggregateId] {
    def compare(a: AggregateId, b: AggregateId) = ev.compare(a, b)
  }

  sealed trait Error

  final case class ErrorExistsAlready(id: AggregateId) extends Error

  final case class ErrorCommandFailure(message: String) extends Error

  final case class DatabaseError(err: Database.Error) extends Error

  final case class ErrorCannotFindHandler(commandData: String) extends Error

  final case class Errors(err: NEL[Error]) extends Error

  //TODO: remove 2nd layer of EitherT and just use leftMap on the 1st Left
  type DatabaseWithAnyFailure[E, Err, A] = EitherT[EventDatabaseWithFailure[E, ?], Err, A]
  type DatabaseWithAggregateFailure[E, A] = DatabaseWithAnyFailure[E, Error, A]

  def dbAction[E, A](dbActions: Database.EventDatabaseWithFailure[E, A]): DatabaseWithAggregateFailure[E, A] =
    EitherT[EventDatabaseWithFailure[E, ?], Error, A](dbActions.map(Right(_)))

  val NewAggregateVersion = -1

  final case class VersionedAggregateData[D](id: AggregateId, data: D, version: Int)

  //TODO: remove StateT - it has lost purpose now
  type AggregateDefAnyD[E, D, A] = StateT[DatabaseWithAggregateFailure[E, ?], D, A]
  type AggregateDef[E, D, A] = AggregateDefAnyD[E, VersionedAggregateData[D], A]

  implicit def eventDatabaseWithFailureMonad[E]: MonadError[DatabaseWithAggregateFailure[E, ?], Error] = EitherT.catsDataMonadErrorForEitherT[EventDatabaseWithFailure[E, ?], Error]

  implicit def aggregateDefMonad[E, D]: MonadState[AggregateDef[E, D, ?], VersionedAggregateData[D]] = StateT.catsDataMonadStateForStateT[DatabaseWithAggregateFailure[E, ?], VersionedAggregateData[D]]

  def pure[E, A](x: A): DatabaseWithAggregateFailure[E, A] = eventDatabaseWithFailureMonad[E].pure(x)

  def fail[E, A](x: Error): DatabaseWithAggregateFailure[E, A] = eventDatabaseWithFailureMonad[E].raiseError[A](x)

  type CommandHandlerResult[E] = ValidatedNel[Aggregate.Error, List[E]]

  def emitEvent[E](ev: E): CommandHandlerResult[E] = Validated.valid(List(ev))

  def emitEvents[E](evs: List[E]): CommandHandlerResult[E] = Validated.valid(evs)

  implicit val nelErrorSemigroup: Semigroup[NEL[Error]] = SemigroupK[NEL].algebra[Error]

  def failCommand[E, A](err: String): ValidatedNel[Aggregate.Error, A] = Validated.invalid(NEL.of(ErrorCommandFailure(err)))

  sealed trait EventHandlerResult[+D] {
    def aggregateData: D
  }
  final case class JustData[D](aggregateData: D) extends EventHandlerResult[D]
  final case class DataAndSnapshot[D, A: Database.Serializable](aggregateData: D, snapshotInfo: A) extends EventHandlerResult[D] {
    def serializer = implicitly[Database.Serializable[A]]
  }

}

trait AggregateBase {

  import Aggregate._

  type Event
  type Command
  type AggregateData
  type AggregateSnapshot

  def convertSnapshotToData(s: AggregateSnapshot): Option[AggregateData]

  def tag: EventTagAux[Event]

  type CommandHandler = Command => AggregateData => CommandHandlerResult[Event]
  type EventHandler = Event => AggregateData => EventHandlerResult[AggregateData]

  protected def eventHandler: EventHandler

  protected def commandHandler: CommandHandler

  protected def initData: AggregateData

  type AggregateState = VersionedAggregateData[AggregateData]

  type AggregateDefinition[A] = AggregateDef[Event, AggregateData, A]

  def defineAggregate[A](a: AggregateState => DatabaseWithAggregateFailure[Event, (AggregateState, A)]): AggregateDefinition[A] = StateT[DatabaseWithAggregateFailure[Event, ?], AggregateState, A](a)

  def liftAggregateReadState[A](a: AggregateState => DatabaseWithAggregateFailure[Event, A]): AggregateDefinition[A] = defineAggregate[A](s => a(s).map(ret => (s, ret)))

  def liftAggregate[A](a: DatabaseWithAggregateFailure[Event, A]): AggregateDefinition[A] = defineAggregate[A](s => a.map(ret => (s, ret)))

  def liftToAggregateDef[A](f: DatabaseWithAggregateFailure[Event, A]): AggregateDefinition[A] = defineAggregate(s => f.map((s, _)))

  def newState(id: AggregateId) = new AggregateState(id, initData, NewAggregateVersion)
}

trait Aggregate[E, C, D, S] extends AggregateBase {

  import Aggregate._

  type Event = E
  type Command = C
  type AggregateData = D
  type AggregateSnapshot = S

  implicit protected def snapshotSerializer: Database.Serializable[S] // = implicitly[Database.Serializable[S]]

  protected def createTag(id: String)(implicit eventSerialisation: Serializable[E]) = Aggregate.createTag[E](id)

  def handleCommand(cmd: C, retryCount: Int = 10): AggregateDefinition[Unit] = {

    val result = for {
      _ <- readAllEventsAndCatchUp
      events <- handleCmd(cmd)
      _ <- liftAggregateReadState(vs => dbAction(Database.appendEvents(tag, vs.id, vs.version, events)))
      _ <- addEvents(events)
    } yield ()

    defineAggregate { s =>
      new DatabaseWithAggregateFailure(
        result.run(s).value.recoverWith {
          case ErrorUnexpectedVersion(_, _) if retryCount > 0 => handleCommand(cmd, retryCount - 1).run(s).value
        }
      )
    }
  }

  def loadAndHandleCommand(id: AggregateId, cmd: C): DatabaseWithAggregateFailure[E, AggregateState] = {
    val snapshot = dbAction(Database.readSnapshot[E, S](tag, id))
    val state = snapshot.map[AggregateState] { resp =>
      convertSnapshotToData(resp.data).map { data =>
        VersionedAggregateData(id, data, resp.version)
      }.getOrElse(newState(id))
    }
    val recoveredState = new DatabaseWithAggregateFailure(
      state.value.recoverWith {
        case _ => eventDatabaseWithFailureMonad.pure(newState(id)).value
      }
    )
    recoveredState.flatMap(s => handleCommand(cmd).runS(s))
  }

  private def handleCmd(cmd: C): AggregateDefinition[List[E]] = liftAggregateReadState(vs =>
    EitherT.fromEither[EventDatabaseWithFailure[E, ?]](
      commandHandler(cmd)(vs.data).fold[Error Either List[E]](err => Left(Errors(err)), Right(_))
    ))

  private def addEvents(evs: List[E], snapshotCmd: Option[Database.SaveSnapshot[E, _]] = None): AggregateDefinition[Unit] =
    evs match {
      case ev :: others => addEvent(ev).flatMap(cmd => addEvents(others, cmd.orElse(snapshotCmd)))
      case Nil => snapshotCmd match {
        case Some(cmd) => liftAggregate(dbAction(Database.lift(cmd)))
        case None      => liftAggregate(pure(()))
      }
    }

  private def addEvent(ev: E): AggregateDefinition[Option[Database.SaveSnapshot[E, _]]] =
    defineAggregate { vs =>
      val result = eventHandler(ev)(vs.data)
      val newVersion = vs.version + 1
      val newState = vs.copy(data = result.aggregateData, version = newVersion)

      result match {
        case resp @ DataAndSnapshot(_, snapshot) =>
          val cmd = Database.SaveSnapshot(tag, vs.id, newVersion, snapshot)(resp.serializer)
          pure((newState, Some(cmd)))
        case _ => pure((newState, None))
      }
    }

  private def readAllEventsAndCatchUp: AggregateDefinition[Unit] =
    liftAggregateReadState(vs => dbAction(Database.readNewEvents[E](tag, vs.id, vs.version))).flatMap { response =>
      addEvents(response.events).flatMap { _ =>
        if (!response.endOfStream) readAllEventsAndCatchUp
        else liftAggregate(pure(()))
      }
    }
}

