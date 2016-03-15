
import java.util.concurrent.Executors

import Cqrs.Aggregate._
import Cqrs.Database.EventSerialisation
import Cqrs.DbAdapters.EventStore._
import cats.data.Xor
import com.twitter
import com.twitter.util.FuturePool
import shapeless.HNil

import scala.util.{Failure, Try, Success}

//import Cqrs.DbAdapters.InMemoryDb._
import Domain.Counter.{CounterAggregate, Create}
import Domain.Door.{DoorAggregate, Register}

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration._

import io.finch._
import io.finch.circe._
import io.circe.generic.auto._
import com.twitter.finagle.Http

import scala.concurrent.ExecutionContext.Implicits.global

object Eventflow {

  def actions1 = {
    import Domain.Counter._
    import CounterAggregate._
    for {
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
    } yield ()
  }

  def actions2 = {
    import Domain.Counter._
    import CounterAggregate._
    for {
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
    //     _ <- handleCommand(Decrement)
    } yield ()
  }

  def doorActions = {
    import Domain.Door._
    import DoorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield ()
  }

  def main(args: Array[String]) {
    import Domain._

//    val db = newInMemoryDb(CounterProjection, DoorProjection, OpenDoorsCountersProjection)
    val db = newEventStoreConn(CounterProjection, DoorProjection, OpenDoorsCountersProjection)

    def act[E: EventSerialisation, A](actions: DatabaseWithAggregateFailure[E, A]) =
      Await.result(db.runAggregate(actions), 10.seconds)

//    val ret = for {
//      c1 <- act(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
//      c1 <- act(actions1 runS c1)
//      d1 <- act(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
//      d1 <- act(doorActions runS d1)
//      c1 <- act(actions2 runS c1)
//      d1 <- act(doorActions runS d1)
//    } yield ()
//
//    ret.fold(err => {
//      println("Error occurred: " + err);
//    }, r => {
//      println("OK"); r
//    })
//
//    print(db.getProjectionData(CounterProjection))
//    print(db.getProjectionData(DoorProjection))
//    print(db.getProjectionData(OpenDoorsCountersProjection))


    implicit def scalaToTwitterTry[T](t: Try[T]): twitter.util.Try[T] = t match {
      case Success(r) => twitter.util.Return(r)
      case Failure(ex) => twitter.util.Throw(ex)
    }

    implicit def scalaToTwitterFuture[T](f: Future[T])(implicit ec: ExecutionContext): twitter.util.Future[T] = {
      val promise = twitter.util.Promise[T]()
      f.onComplete(promise update _)
      promise
    }

    val futurePool = FuturePool(Executors.newCachedThreadPool())

import shapeless.::
    val counter: Endpoint[Unit] = post("counter" / string :: body.as[Counter.Command]) mapOutputAsync {
      case id :: cmd :: HNil => {
        scalaToTwitterFuture(db.runAggregate(CounterAggregate.loadAndHandleCommand(id, cmd)).map {
          case Xor.Right(_) => Ok(())
          case Xor.Left(err) => PreconditionFailed(new Exception(err.toString))
        })
      }
      case _ => twitter.util.Future.value(InternalServerError(new Exception("Cannot handle input")))
    }
    val counterRead: Endpoint[Int] = get("counter" / string) mapOutputAsync {
      case id => futurePool(
        db.getProjectionData(CounterProjection).flatMap(_.get(id)).map(Ok(_)).getOrElse(NotFound(new Exception("Cannot find such counter")))
      )
      case _ => twitter.util.Future.value(InternalServerError(new Exception("Cannot handle input")))
    }
    val api = counter :+: counterRead
    val server = Http.serve(":8080", api.toService)
  }
}

