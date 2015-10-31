package Cqrs

import cats.data.Xor
import cats.state.StateT

import Cqrs.Aggregate.{ AggregateDef, AggregateState, EventDatabaseWithFailure, Error }
import Cqrs.DbAdapters.InMemoryDb.{EventSerialisation, DbBackend, runInMemoryDb}
import lib.{ HList, HNil }
import lib.HList._

object BatchRunner {
  def forDb(db: DbBackend) = BatchRunner[HNil.type](db, HNil)

  sealed trait ProjectionHandlers[E, PROJS <: HList] {
    def apply(db: DbBackend, pr: PROJS): PROJS
  }
  object ProjectionHandlers extends LowPrioProjectionHandlers {
    implicit def collectHandlers[E, D, T <: HList](implicit h: Projection.Handler[E, D], iTail: ProjectionHandlers[E, T]): ProjectionHandlers[E, Projection[D] :: T] = {
      new ProjectionHandlers[E, Projection[D] :: T] {
        def apply(db: DbBackend, pr: Projection[D] :: T) = pr.head.applyNewEventsFromDb(db) :: iTail(db, pr.tail)
      }
    }
  }
  trait LowPrioProjectionHandlers extends LowestPrioProjectionHandlers {
    implicit def collectSkipHandlers[E, D, T <: HList](implicit iTail: ProjectionHandlers[E, T]): ProjectionHandlers[E, Projection[D] :: T] =
      new ProjectionHandlers[E, Projection[D] :: T] {
        def apply(db: DbBackend, pr: Projection[D] :: T) = pr.head :: iTail(db, pr.tail)
      }
  }
  trait LowestPrioProjectionHandlers {
    implicit def collectEndHandlers[E, T <: HNil]: ProjectionHandlers[E, HNil.type] =
      new ProjectionHandlers[E, HNil.type] {
        def apply(db: DbBackend, pr: HNil.type) = pr
      }
  }

}

final case class BatchRunner[PROJS <: HList](db: DbBackend, projections: PROJS) {

  type Self = BatchRunner[PROJS]

  import BatchRunner.ProjectionHandlers

  type DbActions[A] = StateT[(Self, Error) Xor ?, Self, A]

  def addProjection[D](proj: Projection[D]): BatchRunner[Projection[D] :: PROJS] = copy(projections = proj :: projections)

  def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit eventSerialiser: EventSerialisation[E], _projHandlers: ProjectionHandlers[E, PROJS]): DbActions[A] =
    new DbActions[A](
      Xor.right((runner: BatchRunner[PROJS]) => {
        println("XX> " + db)
        val failureOrRes = runInMemoryDb(runner.db, actions)
        val dbAndFailureOrRes = failureOrRes.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
        dbAndFailureOrRes._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(db = dbAndFailureOrRes._1).runProjections, a)))
      })
    )

  def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit eventSerialiser: EventSerialisation[E], _projHandlers: ProjectionHandlers[E, PROJS]): DbActions[(AggregateState[S], A)] = {
    val actions = aggregate.run(prev._1)
    db(actions)
  }

  def runProjections[E](implicit _projHandlers: ProjectionHandlers[E, PROJS]) =
    copy(projections = _projHandlers(db, projections))

  def run[A](actions: DbActions[A]): (Self, Error) Xor (Self, A) = actions.run(this)
}
