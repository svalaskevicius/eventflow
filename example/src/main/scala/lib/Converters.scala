package lib

import com.twitter

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import scala.language.implicitConversions

import scala.concurrent.ExecutionContext.Implicits.global

object Converters {

  implicit def scalaToTwitterTry[T](t: Try[T]): twitter.util.Try[T] = t match {
    case Success(r) => twitter.util.Return(r)
    case Failure(ex) => twitter.util.Throw(ex)
  }

  implicit def scalaToTwitterFuture[T](f: Future[T])(implicit ec: ExecutionContext): twitter.util.Future[T] = {
    val promise = twitter.util.Promise[T]()
    f.onComplete(promise update _)
    promise
  }

}
