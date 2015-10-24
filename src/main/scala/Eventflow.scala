
import Cqrs.InMemoryDb._
import Cqrs.BatchRunner

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
    } yield (())
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
    } yield (())
  }

  def doorActions1 = {
    import Domain.Door._
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }
  def doorActions2 = {
    import Domain.Door._
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }

  def main(args: Array[String])
  {
    import Domain._

    val runner = BatchRunner.empty.
      addDb(newDb[Counter.Event]).
      addDb(newDb[Door.Event]).
      addProjection(CounterProjection.emptyCounterProjection).
      addProjection(DoorProjection.emptyDoorProjection)

    {
      import runner._
      val db_ = run(
        for {
          c1 <- db(Counter.startCounter("test counter"))
          c1 <- db(c1, actions1)
          d1 <- db(Door.registerDoor("golden gate"))
          d1 <- db(d1, doorActions1)
          c1 <- db(c1, actions2)
          d1 <- db(d1, doorActions2)
        } yield (())) .
        fold(err => {println("Error occurred: " + err._2); err._1}, r => {println("OK"); r._1})
      println(db_)
    }
  }
}

