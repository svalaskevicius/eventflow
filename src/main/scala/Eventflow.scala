
import Cqrs.BatchRunner
import Cqrs.DbAdapters.EventStore._
import Domain.Counter.{ CounterAggregate, Create }
import Domain.Door.{ DoorAggregate, Register }

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

    def printRunner[DB: pprint.PPrint, PROJS <: shapeless.HList: pprint.PPrint](runner: BatchRunner[DB, PROJS]) = {
      import pprint._
      println("============================")
      println("DB:")
      pprintln(runner.db, colors = pprint.Colors.Colored)
      println("Projections:")
      pprintln(runner.projections, colors = pprint.Colors.Colored)
      println("============================")
    }

    val runner = BatchRunner.forDb(newEventStoreConn).
      addProjection(CounterProjection.emptyCounterProjection).
      addProjection(DoorProjection.emptyDoorProjection).
      addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

    {
      import runner._
      val runner1 = run(
        for {
          c1 <- db(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
          c1 <- continueWithCommand(c1, actions1)
          d1 <- db(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
          d1 <- continueWithCommand(d1, doorActions)
          c1 <- continueWithCommand(c1, actions2)
          d1 <- continueWithCommand(d1, doorActions)
        } yield ()
      ).
        fold(err => { println("Error occurred: " + err._2); err._1 }, r => { println("OK"); r._1 })

      printRunner(runner1)

      val runner2 = runner1.addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

      printRunner(runner2)
    }
  }
}

