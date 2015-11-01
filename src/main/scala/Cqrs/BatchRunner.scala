package Cqrs

import cats.data.Xor
import cats.state.StateT

import Cqrs.Aggregate.{ AggregateDef, AggregateState, EventDatabaseWithFailure, Error }
import Cqrs.DbAdapters.InMemoryDb.{EventSerialisation, DbBackend, runInMemoryDb}
import cats.~>

import shapeless._
import lib.HList.{KMapper, kMap}

object BatchRunner {
  def forDb(db: DbBackend) = BatchRunner[HNil.type](db, HNil)

}

final case class BatchRunner[PROJS <: HList](db: DbBackend, projections: PROJS) {

  type Self = BatchRunner[PROJS]

  type DbActions[A] = StateT[(Self, Error) Xor ?, Self, A]

  def addProjection[D](proj: Projection[D])(implicit m: KMapper[Projection, PROJS, Projection, PROJS]): BatchRunner[Projection[D] :: PROJS] = copy(projections = proj :: projections).runProjections

  def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit eventSerialiser: EventSerialisation[E], m: KMapper[Projection, PROJS, Projection, PROJS]): DbActions[A] =
    new DbActions[A](
      Xor.right((runner: BatchRunner[PROJS]) => {
        println("XX> " + db)
        val failureOrRes = runInMemoryDb(runner.db, actions)
        val dbAndFailureOrRes = failureOrRes.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
        dbAndFailureOrRes._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(db = dbAndFailureOrRes._1).runProjections, a)))
      })
    )

  def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit eventSerialiser: EventSerialisation[E], m: KMapper[Projection, PROJS, Projection, PROJS]): DbActions[(AggregateState[S], A)] = {
    val actions = aggregate.run(prev._1)
    db(actions)
  }

  object runProjection extends (Projection ~> Projection) {
    def apply[D](a : Projection[D]): Projection[D] = a.applyNewEventsFromDb(db)
  }

  def runProjections(implicit m: KMapper[Projection, PROJS, Projection, PROJS]) = copy(projections = kMap(projections, runProjection))

  def run[A](actions: DbActions[A]): (Self, Error) Xor (Self, A) = actions.run(this)
}
