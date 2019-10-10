package hcb

import java.net.ServerSocket

import scala.concurrent.ExecutionContext

import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import fs2.{Chunk, Pull, Stream}
import org.http4s.{HttpApp, Request, Uri}
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeServerBuilder
import utest._

object StreamProgress
{
  def logMessage(prefix: String)(totalBytes: Long): Long => String = {
      val totalSize = totalBytes / 1000000L
      progressBytes =>
        val progress = progressBytes / 1000000L
        val percent = Either.catchNonFatal(100 * progress / totalSize).getOrElse("???")
        s"$prefix: $progress/$totalSize M [$percent%]"
  }

  def logProgress
  (msg: Long => String)
  (stepSize: Long, progress: Long, lastLog: Long, chunkSize: Long)
  : IO[Long] = {
    val newProgress = progress + chunkSize
    val nextStep = lastLog + stepSize
    if (newProgress > nextStep) IO(println(msg(newProgress))).as(nextStep)
    else IO.pure(lastLog)
  }

  def logged
  (prefix: String)
  (stepSize: Long, totalSize: Long)
  : Stream[IO, Byte] => Pull[IO, Byte, Unit] = {
    val msg: Long => String = logMessage(prefix)(totalSize)
    def spin(bytes: Stream[IO, Byte])(progress: Long, lastLog: Long): Pull[IO, Byte, Unit] =
      bytes.pull.uncons.flatMap {
        case Some((chunk, tail)) =>
          for {
            _ <- Pull.output(chunk)
            newLastLog <- Pull.eval(logProgress(msg)(stepSize, progress, lastLog, chunk.size))
            _ <- spin(tail)(progress + chunk.size, newLastLog)
          } yield ()
        case None => Pull.done
      }
    spin(_)(0, 0)
  }

  def apply
  (prefix: String)
  (stepSize: Long, totalSize: Long)
  : Stream[IO, Byte] => Stream[IO, Byte] =
    _.through(logged(prefix)(stepSize, totalSize)(_).stream)
}

object Resources
{
  implicit def ec: ExecutionContext =
    ExecutionContext.global

  implicit def cs: ContextShift[IO] =
    IO.contextShift(ec)

  implicit def timer: Timer[IO] =
    IO.timer(ec)
}

object FreePort
{
  def port(socket: ServerSocket): IO[Int] =
    IO(socket.setReuseAddress(true)) *> IO(socket.getLocalPort)

  def find: IO[Int] =
    IO(new ServerSocket(0)).bracket(port)(socket => IO(socket.close()))
}

object ServerTest
{
  def serve
  (port: Int)
  (routes: HttpApp[IO])
  (implicit cs: ContextShift[IO], timer: Timer[IO])
  : Resource[IO, Server[IO]] =
    BlazeServerBuilder[IO].withHttpApp(routes).bindHttp(port, "localhost").resource

  def stream[A]
  (routes: HttpApp[IO])
  (test: (Client[IO], Uri) => IO[A])
  (implicit cs: ContextShift[IO], timer: Timer[IO])
  : Stream[IO, Unit] =
    for {
      client <- BlazeClientBuilder[IO](Resources.ec).stream
      port <- Stream.eval(FreePort.find)
      _ <- Stream.resource(serve(port)(routes))
      _ <- Stream.eval(test(client, Uri.unsafeFromString(s"http://localhost:$port")))
    } yield ()

  def apply[A]
  (routes: HttpApp[IO])
  (test: (Client[IO], Uri) => IO[A])
  (implicit cs: ContextShift[IO], timer: Timer[IO])
  : Unit =
    stream(routes)(test).compile.drain.unsafeRunSync
}

object BugTest
extends TestSuite
{
  import Resources.{cs, timer}

  val chunkLength: Int =
    10000

  val chunkCount: Long =
    150000

  val totalLength: Long =
    chunkLength * chunkCount

  val byteChunk: Chunk[Byte] =
    Chunk.bytes(Array.fill(chunkLength.toInt)('a'))

  val stepSize: Long =
    totalLength / 10

  def bodyStream: Stream[IO, Byte] =
    Stream.unfoldChunk(0)(i => if (i == chunkCount) None else Some((byteChunk, i + 1)))
      .covary[IO]
      .through(StreamProgress("serve")(stepSize, totalLength))

  def routes: HttpApp[IO] = {
    import org.http4s.dsl.io._
    HttpApp.liftF(Ok(body = bodyStream))
  }

  def testFetch(client: Client[IO], uri: Uri): IO[Unit] =
    client.fetch(Request[IO](uri = uri))(_.body.compile.to[Array])
      .void

  def testStream(client: Client[IO], uri: Uri): IO[Unit] =
    client.stream(Request[IO](uri = uri))
      .flatMap(_.body)
      .through(StreamProgress("download")(stepSize, totalLength))
      .compile
      .to[Array]
      .void

  def tests: Tests =
    Tests {
      "bug" - {
        println(s"$chunkLength / $chunkCount / $totalLength / $stepSize")
        ServerTest(routes)(testStream)
      }
    }
}
