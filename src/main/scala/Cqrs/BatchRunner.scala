package Cqrs

import Cqrs.Database.{ Backend, EventSerialisation }
import cats.data.Xor
import cats.state.StateT

import Cqrs.Aggregate._
import cats.~>

import shapeless._
import lib.HList.{ KMapper, kMap }

object BatchRunner {
  def forDb[Db: Backend](db: Db) = BatchRunner[Db, HNil.type](db, HNil)

}

final case class BatchRunner[Db: Backend, PROJS <: HList](db: Db, projections: PROJS)(implicit m: KMapper[Projection, PROJS, Projection, PROJS]) {

  type Self = BatchRunner[Db, PROJS]

  type DbActions[A] = StateT[(Self, Error) Xor ?, Self, A]

  def addProjection[D](proj: Projection[D]): BatchRunner[Db, Projection[D] :: PROJS] = copy(projections = proj :: projections).runProjections

  //TODO: rename dbs
  def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit eventSerialiser: EventSerialisation[E]): DbActions[A] =
    new DbActions[A](
      Xor.right((runner: BatchRunner[Db, PROJS]) => {
        val failureOrRes = Database.runDb(runner.db, actions)
        val dbAndFailureOrRes = failureOrRes.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
        dbAndFailureOrRes._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(db = dbAndFailureOrRes._1).runProjections, a)))
      })
    )

  def db[E, A, S](aggregateState: AggregateState[S], aggregateActions: AggregateDef[E, S, A])(implicit eventSerialiser: EventSerialisation[E]): DbActions[(AggregateState[S], A)] = {
    db(aggregateActions.run(aggregateState))
  }

  object runProjection extends (Projection ~> Projection) {
    def apply[D](a: Projection[D]): Projection[D] = {
      val ret = a.applyNewEventsFromDb(db)
      ret.fold(e => { println("Error while running projection: " + e); a }, identity)
    }
  }

  def runProjections = copy(projections = kMap(projections, runProjection))

  def run[A](actions: DbActions[A]): (Self, Error) Xor (Self, A) = actions.run(this)

  def withDb(f: Db => Db) = copy(db = f(db))
}
