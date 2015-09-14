
import Domain.Counter._
import Cqrs.Aggregate._
import Cqrs.InMemoryDb._

object Eventflow {

  def main(args: Array[String]) {
   val result = for {
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
    println(result)
    val result1 = runCounter(result)
    println(result1)
   // println(result fold(err => "Error occurred: " + err, _ => "OK"))
    println("------------")
    runInMemoryDb(newDb)(result1) fold(err => println("Error occurred: " + err), _ => println("OK"))
  }

}


