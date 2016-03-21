

import Domain._
import Cqrs.DbAdapters.EventStore._
import java.util.concurrent.Executors

import Cqrs.Aggregate
import cats.data.Xor
import com.twitter
import com.twitter.util.FuturePool
import io.finch.{Endpoint, _}
import io.finch.circe._
import io.circe.generic.auto._
import com.twitter.finagle.Http
import lib.Converters._
import shapeless.HNil
import shapeless.::

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

object EventflowExample {

  val futurePool = FuturePool(Executors.newCachedThreadPool())

  val db = newEventStoreConn(CounterProjection, DoorProjection, OpenDoorsCountersProjection)

  val counter = commandEndpoint("counter", CounterAggregate)

  val counterRead = projectionEndpoint("counter" / string) {
    id => db.getProjectionData(CounterProjection).flatMap(_.get(id))
  }

  val countersRead = projectionEndpoint("counters") {
    _ => db.getProjectionData(CounterProjection).map(_.keySet.toList.map(_.v))
  }

  val door = commandEndpoint("door", DoorAggregate)

  val doorRead = projectionEndpoint("door" / string) {
    id => db.getProjectionData(DoorProjection).flatMap(_.get(id))
  }

  val doorsRead = projectionEndpoint("doors") {
    _ => db.getProjectionData(DoorProjection).map(_.keySet.toList.map(_.v))
  }

  def main(args: Array[String]) {
    val api = counter :+: counterRead :+: countersRead :+: door :+: doorRead :+: doorsRead
    val server = Http.serve(":8080", api.toService)
  }

  private def commandEndpoint[E, C: DecodeRequest : ClassTag, D](path: String, aggregate: Aggregate[E, C, D]) =
    post(path / string :: body.as[C]) mapOutputAsync {
      case id :: cmd :: HNil =>
        scalaToTwitterFuture(db.runAggregate(aggregate.loadAndHandleCommand(id, cmd)).map {
          case Xor.Right(_) => Ok(())
          case Xor.Left(err) => PreconditionFailed(new Exception(err.toString))
        })
      case _ => twitter.util.Future.value(InternalServerError(new Exception("Cannot handle input")))
    }

  private def projectionEndpoint[R, B](r: Endpoint[R])(f: R => Option[B]): Endpoint[B] =
    get(r) mapOutputAsync { r =>
      futurePool(
        f(r).map(Ok(_)).getOrElse(NotFound(new Exception("Cannot find the requested item.")))
      )
    }
}

