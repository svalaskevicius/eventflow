
import Domain.Counter._

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
    result fold(err => println("Error occurred: " + err), _ => println("OK"))
  }

}


