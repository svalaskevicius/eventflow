import Cqrs.Aggregate._
import Cqrs.{Aggregate, BatchRunner}
import Cqrs.Database._
import cats.data.Xor
import Cqrs.DbAdapters.InMemoryDb._
import Cqrs.Aggregate.AggregateId
import shapeless.HList

trait AggregateSpec {

  implicit class GivenSteps[Db: Backend, PROJS <: HList](val runner: BatchRunner[Db, PROJS]) {
    type Self = GivenSteps[Db, PROJS]

    def event[E: EventSerialisation](tag: Aggregate.Tag, id: AggregateId, e: E): Self =
      GivenSteps( runner.withDb { db =>
        addEvents(runner.db, tag, id, List(e)).fold(
          err => throw new scala.Error(err.toString),
          identity
        )
      })
  }

  case class WhenSteps[Db: Backend, PROJS <: HList](runner: BatchRunner[Db, PROJS], startingDbOpNr: Int) {

    def command[E: EventSerialisation, C, D](aggregate: Aggregate[E, C, D], id: AggregateId, cmd: C) = {
      WhenSteps(
        runner.run(runner.db(aggregate.newState(id), aggregate.handleCommand(cmd)))
          .fold(err => throw new scala.Error(err.toString), _._1),
        startingDbOpNr
      )
    }
  }

  case class ThenSteps[Db: Backend, PROJS <: HList](runner: BatchRunner[Db, PROJS], startingDbOpNr: Int) {

    type Self = ThenSteps[Db, PROJS]

    def newEvents[E: EventSerialisation](tag: Aggregate.Tag, aggregateId: AggregateId): List[E] =
      readEvents(runner.db, startingDbOpNr, tag, aggregateId)
        .fold(err => throw new scala.Error(err.toString), _._2)
  }

  def newDbRunner = BatchRunner.forDb(newInMemoryDb)

  def given[Db: Backend, PROJS <: HList](steps: GivenSteps[Db, PROJS]) = {
    new WhenStepFlow(steps.runner)
  }

  class WhenStepFlow[Db: Backend, PROJS <: HList](runner: BatchRunner[Db, PROJS]) {
    def when(steps: WhenSteps[Db, PROJS] => WhenSteps[Db, PROJS] ) = {
      val v = readDbVersion(runner.db).fold(err => throw new scala.Error(err.toString), identity)
      new ThenStepFlow(steps(WhenSteps(runner, v)))
    }
  }

  class ThenStepFlow[Db: Backend, PROJS <: HList](whenSteps: WhenSteps[Db, PROJS]) {
    def thenCheck[R](steps: ThenSteps[Db, PROJS] => R ): R =
      steps(ThenSteps(whenSteps.runner, whenSteps.startingDbOpNr))
  }

  private def readEvents[E: EventSerialisation, Db](db: Db, fromOperation: Int, tag: Aggregate.Tag, aggregateId: AggregateId)(implicit backend: Backend[Db]) = {
    backend.consumeDbEvents(
      db,
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
    )
  }


  private def readDbVersion[Db](db: Db)(implicit backend: Backend[Db]): Error Xor Int =
    backend.consumeDbEvents(db, 0, (), List()).map(_._1)

  private def addEvents[Db: Backend, E: EventSerialisation](database: Db, tag: Aggregate.Tag, aggregateId: AggregateId, events: List[E]): Error Xor Db = {
    import Aggregate._

    def getVersion(exists: Boolean): EventDatabaseWithFailure[E, Int] =
      if (! exists) pure(0)
      else for {
        pastEvents <- readNewEvents[E](tag, aggregateId, 0)
      } yield pastEvents.lastOption.map(_.version).getOrElse(0)

    val commands = for {
      exists <- doesAggregateExist[E](tag, aggregateId)
      version <- getVersion(exists)
      _ <- appendEvents[E](tag, aggregateId, VersionedEvents(version+1, events))
    } yield ()
    implicitly[Backend[Db]].runDb(database, commands).map(_._1)
  }
}