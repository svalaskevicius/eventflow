
import Cqrs.Aggregate.{AggregateId, EventTag}
import Cqrs.DbAdapters.EventStore
import Cqrs.{ConcreteProjRunner, BatchRunner}
import Cqrs.Database.{EventData2, EventData}
import Cqrs.DbAdapters.EventStore._
import Domain.Counter.{ CounterAggregate, Create }
import Domain.Door.{ DoorAggregate, Register }
import shapeless.HNil

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

//    val pr1 = ConcreteProjRunner(CounterProjection.p, 0)
//    val pr2 = pr1.accept(EventData2(CounterAggregate.tag, AggregateId("a"), 1, Counter.Incremented))
//    val pr3 = pr2.accept(EventData2(CounterAggregate.tag, AggregateId("a"), 1, Counter.Decremented))
//    val pr4 = pr3.accept(EventData2(CounterAggregate.tag, AggregateId("a"), 1, Door.Closed))
//    println(pr3)

        var runner: BatchRunner[EventStore.DbBackend, HNil.type] = null
        runner = BatchRunner.forDb(newEventStoreConn(List(ConcreteProjRunner(CounterProjection.p, 0)), updater => {
          println(s"updated db from db: ${runner.db} => ${updater(runner.db)}")
        }))

        {
          val runner1 = runner.run(
            for {
              c1 <- runner.db(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
              c1 <- runner.continueWithCommand(c1, actions1)
              d1 <- runner.db(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
              d1 <- runner.continueWithCommand(d1, doorActions)
              c1 <- runner.continueWithCommand(c1, actions2)
              d1 <- runner.continueWithCommand(d1, doorActions)
            } yield ()
          ).
            fold(err => { println("Error occurred: " + err._2); err._1 }, r => { println("OK"); r._1 })

          printRunner(runner1)

//          val runner2 = runner1.addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)
//          printRunner(runner2)
        }
//    val runner = BatchRunner.forDb(newEventStoreConn).
//      addProjection(CounterProjection.emptyCounterProjection).
//      addProjection(DoorProjection.emptyDoorProjection).
//      addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)
//
//    {
//      import runner._
//      val runner1 = run(
//        for {
//          c1 <- db(CounterAggregate.loadAndHandleCommand("testcounter", Create("testcounter", 0)))
//          c1 <- continueWithCommand(c1, actions1)
//          d1 <- db(DoorAggregate.loadAndHandleCommand("goldengate", Register("goldengate")))
//          d1 <- continueWithCommand(d1, doorActions)
//          c1 <- continueWithCommand(c1, actions2)
//          d1 <- continueWithCommand(d1, doorActions)
//        } yield ()
//      ).
//        fold(err => { println("Error occurred: " + err._2); err._1 }, r => { println("OK"); r._1 })
//
//      printRunner(runner1)
//
//      val runner2 = runner1.addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)
//
//      printRunner(runner2)
//    }
  }
}

