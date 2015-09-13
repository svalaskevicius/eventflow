
import Domain.Counter._
import Cqrs.Aggregate._
import Cqrs.InMemoryDb._

object Eventflow {

  def main(args: Array[String]) {
    val result = for {
      c <- newCounter("test counter")
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Decrement)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Decrement)
      c <- c.handleCommand(Decrement)
      c <- c.handleCommand(Decrement)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Increment)
      c <- c.handleCommand(Increment)
    } yield c
    println("------------")
    println(result)
    println(result fold(err => "Error occurred: " + err, _ => "OK"))
    println("------------")
    runInMemoryDb(newDb)(result) fold(err => println("Error occurred: " + err), _ => println("OK"))
  }

}


