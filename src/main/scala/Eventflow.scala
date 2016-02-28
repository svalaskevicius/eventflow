
import Cqrs.DbAdapters.InMemoryDb._
import Cqrs.BatchRunner
import Cqrs.Aggregate.AggregateId
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

  def doorActions1 = {
    import Domain.Door._
    import DoorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield ()
  }
  def doorActions2 = {
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

    val runner = BatchRunner.forDb(newInMemoryDb).
      addProjection(CounterProjection.emptyCounterProjection).
      addProjection(DoorProjection.emptyDoorProjection).
      addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

    {
      import runner._
      val runner1 = run(
        for {
          c1 <- db(CounterAggregate.initAggregate(Create("test counter", 0)))
          c1 <- db(c1, actions1)
          d1 <- db(DoorAggregate.initAggregate(Register("golden gate")))
          d1 <- db(d1, doorActions1)
          c1 <- db(c1._1, actions2)
          d1 <- db(d1._1, doorActions2)
        } yield ()
      ).
        fold(err => { println("Error occurred: " + err._2); err._1 }, r => { println("OK"); r._1 })

      printRunner(runner1)

      val runner2 = runner1.addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

      printRunner(runner2)
    }
  }
}

