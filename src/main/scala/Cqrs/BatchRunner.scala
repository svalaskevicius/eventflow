package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.{Backend, EventSerialisation}
import cats.data.Xor
import cats.state.StateT
import cats.~>
import lib.HList.{KMapper, kMap}
import shapeless._
import shapeless.syntax.typeable._

object BatchRunner {
  def forDb[Db: Backend](db: Db) = BatchRunner[Db, HNil.type](db, HNil)

}

final case class BatchRunner[Db: Backend, PROJS <: HList](db: Db, projections: PROJS)(implicit m: KMapper[Projection, PROJS, Projection, PROJS]) {

  type Self = BatchRunner[Db, PROJS]

  type DbActions[A] = StateT[(Self, Error) Xor ?, Self, A]

  def addProjection[D](proj: Projection[D]): BatchRunner[Db, Projection[D] :: PROJS] = copy(projections = proj :: projections).runProjections

  //TODO: rename dbs
  def db[E, A](actions: DatabaseWithAggregateFailure[E, A])(implicit eventSerialiser: EventSerialisation[E]): DbActions[A] =
    new DbActions[A](
      Xor.right((runner: BatchRunner[Db, PROJS]) => {
        Database.runDb(runner.db, actions.value) match {
          case Xor.Left(err) => Xor.left((runner, DatabaseError(err)))
          case Xor.Right((_, Xor.Left(err))) => Xor.left((runner, err))
          case Xor.Right((newDb, Xor.Right(ret))) => Xor.right((runner.copy(db = newDb).runProjections, ret))
        }
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

  def getProjectionData[D: Typeable](name: String): Option[D] = {
    def findProjection[P <: HList](current: P): Option[D] = current match {
      case (x: Projection[_]) :: _ if x.name == name => x.data.cast[D]
      case _ :: xs => findProjection(xs)
      case HNil => None
    }
    findProjection(projections)
  }

  def run[A](actions: DbActions[A]): (Self, Error) Xor (Self, A) = actions.run(this)

  def withDb(f: Db => Db) = copy(db = f(db))
}
