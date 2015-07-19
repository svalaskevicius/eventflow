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
                           private val on: Aggregate[E, C, D]#EventHandler,
                           private val handle: Aggregate[E, C, D]#CommandHandler,
                           private var data: D
                           ) {
  type Errors = List[String]
  type Events = List[E]
  type CommandHandler = C => Reader[D, Errors Xor Events]
  type EventHandler = E => State[D, Unit]

  def handleCommand(cmd: C): Option[Errors] = handle(cmd).run(data) fold (Some(_), onEvents)

  protected def onEvents(evs: Events): Option[Errors] = {
    data = evs.foldLeft(data)((d, e) => on(e).runS(d).run)
    None
  }
}

