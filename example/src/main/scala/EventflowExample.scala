

import Domain._

import Cqrs.DbAdapters.EventStore._
import java.util.concurrent.Executors
import cats.data.Xor
import com.twitter
import com.twitter.util.FuturePool
import io.finch._
import io.finch.circe._
import io.circe.generic.auto._
import com.twitter.finagle.Http
import lib.Converters._
import shapeless.HNil
import shapeless.::


import scala.concurrent.ExecutionContext.Implicits.global

object EventflowExample {

  val futurePool = FuturePool(Executors.newCachedThreadPool())

  val db = newEventStoreConn(CounterProjection, DoorProjection, OpenDoorsCountersProjection)

  val counter = post("counter" / string :: body.as[Counter.Command]) mapOutputAsync {
    case id :: cmd :: HNil =>
      scalaToTwitterFuture(db.runAggregate(CounterAggregate.loadAndHandleCommand(id, cmd)).map {
        case Xor.Right(_) => Ok(())
        case Xor.Left(err) => PreconditionFailed(new Exception(err.toString))
      })
    case _ => twitter.util.Future.value(InternalServerError(new Exception("Cannot handle input")))
  }

  val counterRead = get("counter" / string) mapOutputAsync {
    id => futurePool(
      db.getProjectionData(CounterProjection).flatMap(_.get(id)).map(Ok(_)).getOrElse(NotFound(new Exception("Cannot find such counter")))
    )
  }

  def main(args: Array[String]) {
    val api = counter :+: counterRead
    val server = Http.serve(":8080", api.toService)
  }
}

