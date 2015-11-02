
import Cqrs.DbAdapters.InMemoryDb._
import Cqrs.BatchRunner
import Cqrs.Aggregate.AggregateId

object Eventflow {

  def actions1 = {
    import Domain.Counter._
    import counterAggregate._
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
    import counterAggregate._
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
    import doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield ()
  }
  def doorActions2 = {
    import Domain.Door._
    import doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield ()
  }

  def main(args: Array[String]) {
    import Domain._

    val runner = BatchRunner.forDb(newInMemoryDb).
      addProjection(CounterProjection.emptyCounterProjection).
      addProjection(DoorProjection.emptyDoorProjection).
      addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

    {
      import runner._
      val runner1 = run(
        for {
          c1 <- db(Counter.startCounter(AggregateId("test counter")))
          c1 <- db(c1, actions1)
          d1 <- db(Door.registerDoor(AggregateId("golden gate")))
          d1 <- db(d1, doorActions1)
          c1 <- db(c1, actions2)
          d1 <- db(d1, doorActions2)
        } yield ()
      ).
        fold(err => { println("Error occurred: " + err._2); err._1 }, r => { println("OK"); r._1 })

      @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.OptionPartial", "org.brianmckenna.wartremover.warts.ExplicitImplicitTypes"))
      val _ignore1 = pprint.pprintln(runner1, colors = pprint.Colors.Colored)

      val runner2 = runner1.addProjection(OpenDoorsCountersProjection.emptyOpenDoorsCountersProjection)

      @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.OptionPartial", "org.brianmckenna.wartremover.warts.ExplicitImplicitTypes"))
      val _ignore2 = pprint.pprintln(runner2, colors = pprint.Colors.Colored)
    }
  }
}

