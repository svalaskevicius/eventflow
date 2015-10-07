
import Domain.Counter._
import Cqrs.InMemoryDb._
import Cqrs.EventRouter
import Cqrs.Aggregate._

object Eventflow {

  def actions1(c: CounterAggregate) =
    for {
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Increment)
      _ <- c.handleCommand(Increment)
    } yield (())

  def actions2(c: CounterAggregate) =
    for {
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
      _ <- c.handleCommand(Decrement)
    } yield (())

  def program0 = startCounter(newCounter("test counter"))
  def program1(c: CounterAggregate, s: CounterAggregate#State) = continueAggregate(actions1(c), s)
  def program2(c: CounterAggregate, s: CounterAggregate#State) = continueAggregate(actions2(c), s)

  def main(args: Array[String]) {
    println("------------")
    val ret = for {
      r1 <- runInMemoryDb(newDb)(program0)
      c = r1._2._2
      r2 <- runInMemoryDb(r1._1)(program1(c, r1._2._1))
      r3 <- runInMemoryDb(r2._1)(program2(c, r2._2._1))
    } yield (())
    ret fold(err => println("Error occurred: " + err), _ => println("OK"))
    println("------------")
  }
}

