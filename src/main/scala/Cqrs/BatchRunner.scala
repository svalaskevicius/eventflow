package Cqrs

import cats.data.Xor
import cats.state.StateT

import Cqrs.Aggregate.{ AggregateDef, AggregateState, EventDatabaseWithFailure, Error }
import Cqrs.InMemoryDb.{ DbBackend, runInMemoryDb }
import lib.{ HList, HNil }
import lib.HList._

object BatchRunner {
  def empty = BatchRunner[HNil, HNil.type](HNil, HNil)

  sealed trait ProjectionHandlers[E, PROJS <: HList] {
    def apply(db: DbBackend[E], pr: PROJS): PROJS
  }
  object ProjectionHandlers extends LowPrioProjectionHandlers {
    implicit def collectHandlers[E, D, T <: HList](implicit h: Projection.Handler[E, D], iTail: ProjectionHandlers[E, T]): ProjectionHandlers[E, Projection[D] :: T] = {
      new ProjectionHandlers[E, Projection[D] :: T] {
        def apply(db: DbBackend[E], pr: Projection[D] :: T) = pr.head.applyNewEventsFromDb(db) :: iTail(db, pr.tail)
      }
    }
  }
  trait LowPrioProjectionHandlers extends LowestPrioProjectionHandlers {
    implicit def collectSkipHandlers[E, D, T <: HList](implicit iTail: ProjectionHandlers[E, T]): ProjectionHandlers[E, Projection[D] :: T] =
      new ProjectionHandlers[E, Projection[D] :: T] {
        def apply(db: DbBackend[E], pr: Projection[D] :: T) = pr.head :: iTail(db, pr.tail)
      }
  }
  trait LowestPrioProjectionHandlers {
    implicit def collectEndHandlers[E, T <: HNil]: ProjectionHandlers[E, HNil.type] =
      new ProjectionHandlers[E, HNil.type] {
        def apply(db: DbBackend[E], pr: HNil.type) = pr
      }
  }

}

final case class BatchRunner[DBS <: HList, PROJS <: HList](dbs: DBS, projections: PROJS) {

  type Self = BatchRunner[DBS, PROJS]

  import BatchRunner.ProjectionHandlers

  type DbActions[A] = StateT[(Self, Error) Xor ?, Self, A]

  def addDb[E](db: DbBackend[E]): BatchRunner[DbBackend[E] :: DBS, PROJS] = copy(dbs = db :: dbs)

  def addProjection[D](proj: Projection[D]): BatchRunner[DBS, Projection[D] :: PROJS] = copy(projections = proj :: projections)

  def applyInDb[E, A](actions: EventDatabaseWithFailure[E, A])(db: DbBackend[E]): (DbBackend[E], Error Xor A) = {
    val r = runInMemoryDb(db)(actions)
    r.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
  }

  def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor A], _extr: Extractor[DbBackend[E], DBS], _projHandlers: ProjectionHandlers[E, PROJS]): DbActions[A] =
    new DbActions[A](
      Xor.right((runner: BatchRunner[DBS, PROJS]) => {
        val r = applyUpdate1(runner.dbs, applyInDb(actions))
        r._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(dbs = r._1).runProjections, a)))
      })
    )

  def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor (AggregateState[S], A)], _extr: Extractor[DbBackend[E], DBS], _projHandlers: ProjectionHandlers[E, PROJS]): DbActions[(AggregateState[S], A)] = {
    val actions = aggregate.run(prev._1)
    db(actions)
  }

  def runProjections[E](implicit _extr: Extractor[DbBackend[E], DBS], _projHandlers: ProjectionHandlers[E, PROJS]) = {
    val db = extract[DbBackend[E], DBS](dbs)
    copy(projections = _projHandlers(db, projections))
  }

  def run[A](actions: DbActions[A]): (Self, Error) Xor (Self, A) = actions.run(this)
}
