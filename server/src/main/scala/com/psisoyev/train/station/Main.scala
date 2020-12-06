package com.psisoyev.train.station

import cats.Monad
import cats.data.Kleisli
import cats.effect.{ Concurrent, ConcurrentEffect, Sync, Timer }
import com.psisoyev.train.station.Event.Departed
import com.psisoyev.train.station.arrival.ArrivalValidator.ArrivalError
import com.psisoyev.train.station.arrival.ExpectedTrains.ExpectedTrain
import com.psisoyev.train.station.arrival.{ ArrivalValidator, Arrivals, ExpectedTrains }
import com.psisoyev.train.station.departure.Departures.DepartureError
import com.psisoyev.train.station.departure.{ DepartureTracker, Departures }
import cr.pulsar.schema.circe.circeBytesInject
import cr.pulsar.{ Consumer, Producer }
import fs2.Stream
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{ Request, Response }
import tofu.generate.GenUUID
import tofu.lift.Lift
import tofu.logging.Logging
import tofu.zioInstances.implicits._
import tofu.{ HasProvide, Raise, WithRun }
import zio._
import zio.interop.catz._
import zio.interop.catz.implicits._

object Main extends zio.App {
  type Init[T]      = Task[T]
  type Run[T]       = ZIO[Ctx, Throwable, T]
  type Routes[F[_]] = Kleisli[F, Request[F], Response[F]]

  // TODO is this needed?
  implicit val lift = new Lift[UIO, Run] {
    def lift[A](fa: UIO[A]): Run[A] = fa
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    Task.concurrentEffectWith { implicit CE =>
      Resources
        .make[Init, Run, Event]
        .use { case Resources(config, producer, consumers, logger) =>
          implicit val logging: Logging[Run] = logger
          implicit val tracing: Tracing[Run] = Tracing.make[Run]

          for {
            trainRef      <- Ref.make(Map.empty[TrainId, ExpectedTrain])
            expectedTrains = ExpectedTrains.make[Run](trainRef)
            tracker        = DepartureTracker.make[Run](config.city, expectedTrains)
            routes         = makeRoutes[Init, Run](config, producer, expectedTrains)
            _             <- startHttpServer(config, routes).zipPar(startDepartureTracker(consumers, tracker))
          } yield ()
        }
    }.exitCode

  def makeRoutes[
    Init[_]: Sync,
    Run[_]: Monad: GenUUID: WithRun[*[_], Init, Ctx]: Logging: Tracing
  ](
    config: Config,
    producer: Producer[Init, Event],
    expectedTrains: ExpectedTrains[Run]
  )(implicit
    A: Raise[Run, ArrivalError],
    D: Raise[Run, DepartureError]
  ): Routes[Init] = {
    val arrivalValidator = ArrivalValidator.make[Run](expectedTrains)
    val arrivals         = Arrivals.make[Run](config.city, expectedTrains)
    val departures       = Departures.make[Run](config.city, config.connectedTo)

    new StationRoutes[Init, Run](arrivals, arrivalValidator, producer, departures).routes.orNotFound
  }

  def startHttpServer[Init[_]: ConcurrentEffect: Timer](
    config: Config,
    routes: Routes[Init]
  ): Init[Unit] =
    BlazeServerBuilder[Init](platform.executor.asEC)
      .bindHttp(config.httpPort.value, "0.0.0.0")
      .withHttpApp(routes)
      .serve
      .compile
      .drain

  def startDepartureTracker[Init[_]: Concurrent: GenUUID, Run[_]: HasProvide[*[_], Init, Ctx]](
    consumers: List[Consumer[Init, Event]],
    departureTracker: DepartureTracker[Run]
  ): Init[Unit] =
    Stream
      .emits(consumers)
      .map(_.autoSubscribe)
      .parJoinUnbounded
      .collect { case e: Departed => e }
      .evalMap(e => Tracing.withNewTrace(departureTracker.save(e)))
      .compile
      .drain
}
