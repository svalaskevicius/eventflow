import Cqrs.Aggregate.AggregateId
import Cqrs.Database.FoldableDatabase._
import Cqrs.Database._
import Cqrs.DbAdapters.InMemoryDb._
import Cqrs.{Aggregate, Database, Projection, ProjectionRunner}
import cats.data.Xor

import scala.reflect.ClassTag

trait AggregateSpec {

  type DB = Backend with FoldableDatabase

  def fail(message: String)

  implicit class GivenSteps(val db: DB) {

    def withEvent[E: EventSerialisation](tag: Aggregate.EventTag, id: AggregateId, e: E): GivenSteps = {
      addEvents(db, tag, id, List(e)).fold(
        err => failStop(err.toString),
        identity
      )
      this
    }

    def withEvents[E: EventSerialisation](tag: Aggregate.EventTag, id: AggregateId, evs: E*): GivenSteps = {
        addEvents(db, tag, id, evs.toList).fold(
          err => failStop(err.toString),
          identity
        )
        this
      }
  }

  case class WhenSteps(val db: DB, startingDbOpNr: Long) {

    def command[E: EventSerialisation, C, D](aggregate: Aggregate[E, C, D], id: AggregateId, cmd: C) = {
      db.runAggregate(aggregate.loadAndHandleCommand(id, cmd))
          .leftMap(err => failStop(err.toString))
      this
    }
  }

  case class ThenSteps(val db: DB, startingDbOpNr: Long) {

    def newEvents[E: EventSerialisation](tag: Aggregate.EventTag, aggregateId: AggregateId): List[E] =
      readEvents(db, startingDbOpNr, tag, aggregateId)

    def failedCommandError[E: EventSerialisation, C, D](aggregate: Aggregate[E, C, D], id: AggregateId, cmd: C): Aggregate.Error =
      db.runAggregate(aggregate.loadAndHandleCommand(id, cmd))
        .fold(identity, _ => failStop("Command did not fail, although was expected to"))

    def projectionData[D: ClassTag](projection: Projection[D]) = db.getProjectionData[D](projection)
  }

  def newDb(projections: ProjectionRunner*): GivenSteps = GivenSteps(newInMemoryDb(projections:_*))
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
      steps(ThenSteps(whenSteps.db /* .runProjections */, whenSteps.startingDbOpNr))
  }

  private def readEvents[E: EventSerialisation](db: DB, fromOperation: Long, tag: Aggregate.EventTag, aggregateId: AggregateId) = {
    db.consumeDbEvents(
      fromOperation,
      List.empty[E],
      List(
        EventDataConsumerQuery(
          tag,
          createEventDataConsumer[E, List[E]] { (collection: List[E], event: EventData[E]) =>
            if (event.id == aggregateId) collection :+ event.data else collection
          }
        )
      )
    ).fold(err => failStop("Could not read events: "+err), _._2)
  }

  private def readDbVersion(db: DB): Database.Error Xor Long =
    db.consumeDbEvents(0, (), List()).map(_._1)

  private def addEvents[E: EventSerialisation](database: Backend, tag: Aggregate.EventTag, aggregateId: AggregateId, events: List[E]): Aggregate.Error Xor Unit = {
    import Aggregate._

    val commands = for {
      pastEvents <- dbAction(readNewEvents[E](tag, aggregateId, 0))
      _ <- dbAction(appendEvents[E](tag, aggregateId, pastEvents.lastVersion, events))
    } yield ()
    database.runAggregate(commands)
  }

  private def failStop(message: String) = {
    fail(message)
    throw new scala.Error("Failed with: " + message)
  }

}