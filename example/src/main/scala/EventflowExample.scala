

import Domain._
import Cqrs.DbAdapters.EventStore._
import java.util.concurrent.Executors

import Cqrs.Aggregate
import com.twitter
import com.twitter.util.FuturePool
import io.finch._
import io.circe.generic.auto._
import com.twitter.finagle.Http
import lib.Converters._
import shapeless.HNil

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

object Converters {
  import cats.syntax.show._
  import com.twitter.util.{Return, Throw, Try}
  import io.circe.jawn.decode
  import io.finch.{Decode, Error}
  import io.circe.{Encoder, Decoder}
  import io.finch.Encode
  import io.finch.internal.BufText
  import io.circe.{Json, Printer}

  /**
    * Maps a Circe's [[Decoder]] to Finch's [[Decode]].
    */
  implicit def decodeCirce[A](implicit d: Decoder[A]): Decode[A] = Decode.instance(s =>
    decode[A](s).fold[Try[A]](
      error => Throw[A](Error(error.show)),
      value => Return(value)
    )
  )

  protected def print(json: Json): String = Printer.noSpaces.pretty(json)

  /**
    * Maps Circe's [[Encoder]] to Finch's [[Encode]].
    */
  implicit def encodeCirce[A](implicit e: Encoder[A]): Encode.Json[A] = Encode.json((a, cs) => BufText(print(e(a)), cs))
}

object EventflowExample {

  import Converters._

  val futurePool = FuturePool(Executors.newCachedThreadPool())

  val countersProjection = Counter.newCurrentValueProjection
  val doorsProjection = DoorState.newCurrentStateProjection
  val doorCountersProjection = OpenDoors.newCountersProjection
  val db = newEventStoreConn(countersProjection, doorsProjection, doorCountersProjection)

  val counter = commandEndpoint("counter", CounterAggregate)

  val counterRead = projectionEndpoint("counter" :: string) {
    id => countersProjection.getData.get(id)
  }

  val countersRead = projectionEndpoint("counters") {
    _ => Some(countersProjection.getData.keySet.toList)
  }

  val door = commandEndpoint("door", DoorAggregate)

  val doorRead = projectionEndpoint("door" :: string) {
    id => doorsProjection.getData.get(id)
  }

  val doorsRead = projectionEndpoint("doors") {
    _ => Some(doorsProjection.getData.keySet.toList)
  }

  def main(args: Array[String]) = {
    val api = counter :+: counterRead :+: countersRead :+: door :+: doorRead :+: doorsRead
    val urlPattern = "([0-9.]*)?(:[0-9]*)?".r
    val bind = args.toList match {
      case url :: Nil => url match {
        case urlPattern(host, port) => (if (host != null) host else "") ++ (if (post != null) port else ":8080")
      }
      case _ => ":8080"
    }
    val server = Http.serve(bind, api.toService)
  }

  import shapeless.::

  private def commandEndpoint[E, C: Decode: ClassTag, D, S](path: String, aggregate: Aggregate[E, C, D, S]) =
    post(path :: string :: body.as[C]) mapOutputAsync {
      case id :: cmd :: HNil =>
        scalaToTwitterFuture(db.runAggregate(aggregate.loadAndHandleCommand(id, cmd)).map {
          case Right(_)  => Ok(())
          case Left(err) => PreconditionFailed(new Exception(err.toString))
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

