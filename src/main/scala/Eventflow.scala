
import Domain.Counter._
import Domain.CounterProjection
import Domain.CounterProjection._
import Cqrs.InMemoryDb._
import Cqrs.Aggregate._

object Eventflow {

  def actions1(c: CounterAggregate) = {
    import c._
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

  def actions2(c: CounterAggregate) = {
    import c._
    for {
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
 //     _ <- handleCommand(Decrement)
    } yield (())
  }

  def main(args: Array[String]) {
    val proj = CounterProjection.empty
    val ret = for {
      r1 <- runInMemoryDb(newDb)(startCounter("test counter"))
      c = r1._2._2
      r2 <- runInMemoryDb(r1._1)(c.continue(actions1, r1._2._1))
      p1 = proj.applyNewEventsFromDb(r2._1)
      r3 <- runInMemoryDb(r2._1)(c.continue(actions2, r2._2._1))
      p2 = p1.applyNewEventsFromDb(r3._1)
    } yield (())
    ret fold(err => println("Error occurred: " + err), _ => println("OK"))
  }
}

