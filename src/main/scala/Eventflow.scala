
import Cqrs.Aggregate._
import Cqrs.Database.EventSerialisation
import Cqrs.DbAdapters.EventStore._
import Cqrs.DbAdapters.InMemoryDb._
import Domain.Counter.{CounterAggregate, Create}
import Domain.Door.{DoorAggregate, Register}

import scala.concurrent.Await
import scala.concurrent.duration._

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

    val ret = for {
      c1 <- act(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
      c1 <- act(actions1 runS c1)
      d1 <- act(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
      d1 <- act(doorActions runS d1)
      c1 <- act(actions2 runS c1)
      d1 <- act(doorActions runS d1)
    } yield ()

    ret.fold(err => {
      println("Error occurred: " + err);
    }, r => {
      println("OK"); r
    })

    print(db.getProjectionData(CounterProjection))
    print(db.getProjectionData(DoorProjection))
    print(db.getProjectionData(OpenDoorsCountersProjection))
  }
}

