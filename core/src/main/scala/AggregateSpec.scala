import Cqrs.Aggregate.{ AggregateId, DatabaseWithAggregateFailure }
import Cqrs.Database.FoldableDatabase._
import Cqrs.Database._
import Cqrs.DbAdapters.InMemoryDb._
import Cqrs.{ Aggregate, Database, ProjectionRunner }
import cats.implicits._

import scala.concurrent.Await
import scala.concurrent.duration._

trait AggregateSpec {

  type DB = Backend with FoldableDatabase

  implicit class GivenSteps(val db: DB) {

    def withEvent[E](tag: Aggregate.EventTagAux[E], id: AggregateId, e: E): GivenSteps = {
      addEvents(db, tag, id, List(e)).fold(
        err => failStop(err.toString),
        identity
      )
      this
    }

    def withEvents[E](tag: Aggregate.EventTagAux[E], id: AggregateId, evs: E*): GivenSteps = {
      addEvents(db, tag, id, evs.toList).fold(
        err => failStop(err.toString),
        identity
      )
      this
    }
  }

  case class WhenSteps(val db: DB, startingDbOpNr: Long) {

    def command[E, C, D, S](aggregate: Aggregate[E, C, D, S], id: AggregateId, cmd: C) = {
      act(db, aggregate.loadAndHandleCommand(id, cmd)).swap.foreach { err => failStop(err.toString) }
      this
    }
  }

  case class ThenSteps(val db: DB, startingDbOpNr: Long) {

    def newEvents[E](tag: Aggregate.EventTagAux[E], aggregateId: AggregateId): List[E] =
      readEvents(db, startingDbOpNr, tag, aggregateId)

    def failedCommandError[E, C, D, S](aggregate: Aggregate[E, C, D, S], id: AggregateId, cmd: C): Aggregate.Error =
      act(db, aggregate.loadAndHandleCommand(id, cmd))
        .fold(identity, _ => failStop("Command did not fail, although was expected to"))
  }

  def newDb(projections: ProjectionRunner*): GivenSteps = GivenSteps(newInMemoryDb(projections: _*))

  def newDb: GivenSteps = GivenSteps(newInMemoryDb())

  def given(steps: GivenSteps) = {
    new WhenStepFlow(steps.db)
  }

  class WhenStepFlow(db: DB) {
    def when(steps: WhenSteps => WhenSteps) = {
      val v = readDbVersion(db).fold(err => failStop(err.toString), identity)
      new ThenStepFlow(steps(WhenSteps(db, v)))
    }

    def check[R](steps: ThenSteps => R): R = when(identity).thenCheck(steps)
  }

  class ThenStepFlow(whenSteps: WhenSteps) {
    def thenCheck[R](steps: ThenSteps => R): R =
      steps(ThenSteps(whenSteps.db /* .runProjections */ , whenSteps.startingDbOpNr))
  }

  private def readEvents[E](db: DB, fromOperation: Long, tag: Aggregate.EventTagAux[E], aggregateId: AggregateId) = {
    db.consumeDbEvents(
      fromOperation,
      List.empty[E],
      List(
        createEventDataConsumer(tag) { (collection: List[E], event: EventData[E]) =>
          if (event.id == aggregateId) collection :+ event.data else collection
        }
      )
    ).fold(err => failStop("Could not read events: " + err), _._2)
  }

  private def readDbVersion(db: DB): Database.Error Either Long =
    db.consumeDbEvents(0, (), List()).map(_._1)

  private def addEvents[E](database: Backend, tag: Aggregate.EventTagAux[E], aggregateId: AggregateId, events: List[E]): Aggregate.Error Either Unit = {
    import Aggregate._

    val commands = for {
      pastEvents <- dbAction(readNewEvents[E](tag, aggregateId, 0))
      _ <- dbAction(appendEvents[E](tag, aggregateId, pastEvents.lastVersion, events))
    } yield ()
    act(database, commands)
  }

  private def failStop(message: String) = {
    throw new scala.Error("Failed with: " + message)
  }

  private def act[E, A](db: Backend, actions: DatabaseWithAggregateFailure[E, A]) =
    Await.result(db.runAggregate(actions), 1.second)
}
