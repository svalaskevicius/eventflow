
import Domain.Counter._
import Domain.Door._
import Cqrs.InMemoryDb._
import Cqrs.Aggregate._

object Eventflow {

  def actions1 = {
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
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }
  def doorActions2 = {
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }

  def main(args: Array[String]) {

    import Domain.CounterProjection._
    import Domain.DoorProjection._

    val proj = emptyCounterProjection
    val ret = for {
      // todo: add custom structure to keep DB conn, projections, and binding to run things automatically w/o much typing here:
      // this might need to extract a typeclass for DB and Projection
      r1 <- runInMemoryDb(newDb)(startCounter("test counter"))
      r2 <- runInMemoryDb(r1._1)(actions1.run(r1._2._1))
      p1 = proj.applyNewEventsFromDb(r2._1)
      r3 <- runInMemoryDb(r2._1)(actions2.run(r2._2._1))
      p2 = p1.applyNewEventsFromDb(r3._1)
    } yield (())
    ret fold(err => println("Error occurred: " + err), _ => println("OK"))
    //====
    val doorProj = emptyDoorProjection
    val doorRet = for {
    // todo: add custom structure to keep DB conn, projections, and binding to run things automatically w/o much typing here:
    // this might need to extract a typeclass for DB and Projection
      r1 <- runInMemoryDb(newDb)(registerDoor("golden gate"))
      r2 <- runInMemoryDb(r1._1)(doorActions1.run(r1._2._1))
      p1 = doorProj.applyNewEventsFromDb(r2._1)
      r3 <- runInMemoryDb(r2._1)(doorActions2.run(r2._2._1))
      p2 = p1.applyNewEventsFromDb(r3._1)
    } yield (())
    doorRet fold(err => println("Error occurred: " + err), _ => println("OK"))
  }
}

