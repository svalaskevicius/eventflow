
import Domain.Counter._
import Cqrs.Aggregate._
import Cqrs.InMemoryDb._

object Eventflow {

  def main(args: Array[String]) {
    val result = for {
      counter <- newCounter("test counter")
      // handle command now returns monad to write. USE IT
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Decrement)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Decrement)
      _ <- counter.handleCommand(Decrement)
      _ <- counter.handleCommand(Decrement)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
    } yield counter
    println("------------")
    println(result)
    result fold(err => println("Error occurred: " + err), _ => println("OK"))
    println("------------")
    runInMemoryDb(newDb)(result) fold(err => println("Error occurred: " + err), _ => println("OK"))
  }

}


