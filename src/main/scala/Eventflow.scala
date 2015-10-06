
import Domain.Counter._
import Cqrs.InMemoryDb._

object Eventflow {

  def main(args: Array[String]) {
    val requests = for {
      c <- newCounter("test counter")
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
    println("------------")
    val program = runCounter(requests)
    runInMemoryDb(newDb)(program) fold(err => println("Error occurred: " + err), _ => println("OK"))
    println("------------")
  }
}

