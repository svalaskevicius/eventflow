
import Domain.Counter._

object Eventflow {

  def main(args: Array[String]) {
    val result = for {
      counter <- createCounter(123)
      _ <- counter.handleCommand(Increment)
      _ <- counter.handleCommand(Increment)
    } yield counter
    result fold(err => println("Error occurred: " + err), _ => println("OK"))
  }
}


