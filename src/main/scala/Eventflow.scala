
import Cqrs.DbAdapters.EventStore._
import Cqrs.DbAdapters.InMemoryDb._
import Domain.Counter.{CounterAggregate, Create}
import Domain.Door.{DoorAggregate, Register}

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

    def print[A: pprint.PPrint](a: A) = {
      import pprint._
      println("============================")
      pprintln(a, colors = pprint.Colors.Colored)
      println("============================")
    }

//    val db = newInMemoryDb(CounterProjection, DoorProjection, OpenDoorsCountersProjection)
    val db = newEventStoreConn(CounterProjection, DoorProjection, OpenDoorsCountersProjection)

    val ret = for {
      c1 <- db.runAggregate(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
      c1 <- db.runAggregate(actions1 runS c1)
      d1 <- db.runAggregate(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
      d1 <- db.runAggregate(doorActions runS d1)
      c1 <- db.runAggregate(actions2 runS c1)
      d1 <- db.runAggregate(doorActions runS d1)
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

