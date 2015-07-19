package Cqrs

import cats.state.State
import cats.data.Reader
import cats.data.Xor
import cats.implicits._


object Aggregate {
  def updateState[D](fn: D => D): State[D, Unit] = State(d => (fn(d), ()))

  def emitEvent[E, Errors](ev: E): Errors Xor List[E] = Xor.Right(List(ev))

  def failCommand[Events](err: String): List[String] Xor Events = Xor.Left(List(err))

  def commandHandler[A, B] = Reader
}

class Aggregate[E, C, D] (
                           on: Aggregate[E, C, D]#EventHandler,
                           handle: Aggregate[E, C, D]#CommandHandler,
                           private[this] var data: D
                           ) {
  type Errors = List[String]
  type Events = List[E]
  type CommandHandler = C => Reader[D, Errors Xor Events]
  type EventHandler = E => State[D, Unit]

  def handleCommand(cmd: C): Errors Xor Unit = handle(cmd).run(data) fold (Xor.Left(_), onEvents)

  protected def onEvents(evs: Events): Errors Xor Unit = {
    data = evs.foldLeft(data)((d, e) => on(e).runS(d).run)
    Xor.Right(())
  }
}

