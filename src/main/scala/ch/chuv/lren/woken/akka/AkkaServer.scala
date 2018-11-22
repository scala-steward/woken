/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.akka

import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.pattern.{ Backoff, BackoffSupervisor }
import cats.effect._
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.config.{
  AlgorithmsConfiguration,
  DatabaseConfiguration,
  DatasetsConfiguration,
  WokenConfiguration
}
import ch.chuv.lren.woken.core.{ CoordinatorConfig, Core }
import ch.chuv.lren.woken.service._
import ch.chuv.lren.woken.fp.runNow
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{ Failure, Success }

/**
  * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
  * termination handler to stop the system when the JVM exits.
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.NonUnitStatements"))
class AkkaServer[F[_]: ConcurrentEffect: ContextShift: Timer](
    val databaseServices: DatabaseServices[F],
    override val config: WokenConfiguration
) extends Core
    with CoreActors
    with LazyLogging {

  private lazy val datasetsService: DatasetService = ConfBasedDatasetService(config.config)

  private def mainRouterSupervisorProps = {
    val coordinatorConfig = CoordinatorConfig(
      chronosHttp,
      config.app.dockerBridgeNetwork,
      databaseServices.featuresService,
      databaseServices.jobResultService,
      config.jobs,
      DatabaseConfiguration.factory(config.config)
    )

    val wokenService: WokenClientService = WokenClientService(coordinatorConfig.jobsConf.node)
    val dispatcherService: DispatcherService =
      DispatcherService(DatasetsConfiguration.datasets(config.config), wokenService)
    val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

    BackoffSupervisor.props(
      Backoff.onFailure(
        MasterRouter.props(
          config.config,
          config.app,
          coordinatorConfig,
          datasetsService,
          databaseServices.variablesMetaService,
          dispatcherService,
          algorithmLibraryService,
          AlgorithmsConfiguration.factory(config.config)
        ),
        childName = "mainRouter",
        minBackoff = 100.milliseconds,
        maxBackoff = 1.seconds,
        randomFactor = 0.2
      )
    )
  }

  /**
    * Create and start actor that acts as akka entry-point
    */
  lazy val mainRouter: ActorRef =
    system.actorOf(mainRouterSupervisorProps, name = "entrypoint")

  lazy val cluster: Cluster = Cluster(system)

  def startActors(): Unit = {
    logger.info(s"Start actor system ${config.app.clusterSystemName}...")
    logger.info(s"Cluster has roles ${cluster.selfRoles.mkString(",")}")

    val mediator = DistributedPubSub(system).mediator

    mediator ! DistributedPubSubMediator.Put(mainRouter)

    ClusterClientReceptionist(system).registerService(mainRouter)

    monitorDeadLetters()

  }

  def selfChecks(): Unit = {
    logger.info("Self checks...")

    logger.info("Check configuration of datasets...")

    datasetsService.datasets().filter(_.location.isEmpty).foreach { dataset =>
      dataset.tables.foreach { tableName =>
        databaseServices.featuresService
          .featuresTable(tableName)
          .fold(
            { error: String =>
              logger.error(error)
              System.exit(1)
            }, { table: FeaturesTableService[F] =>
              if (runNow(table.count(dataset.dataset)) == 0) {
                logger.error(
                  s"Table $tableName contains no value for dataset ${dataset.dataset.code}"
                )
                // TODO: no hard exit, rely on Resource to shutdown cleanly
                System.exit(1)
              }
            }
          )
      }
    }

    logger.info("[OK] Self checks passed")
  }

  def unbind(): F[Unit] =
    Sync[F].defer {
      cluster.leave(cluster.selfAddress)
      val ending = system.terminate()
      Async[F].async { cb =>
        ending.onComplete {
          case Success(_)     => cb(Right(()))
          case Failure(error) => cb(Left(error))
        }
      }
    }
}

object AkkaServer extends LazyLogging {

  /** Resource that creates and yields an Akka server, guaranteeing cleanup. */
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      databaseServicesResource: Resource[F, DatabaseServices[F]],
      config: WokenConfiguration
  ): Resource[F, AkkaServer[F]] =
    databaseServicesResource.flatMap { databaseServices =>
      Resource.make(Sync[F].delay {
        val server = new AkkaServer[F](databaseServices, config)

        logger.info(s"Starting Akka server...")
        server.startActors()
        server.selfChecks()

        server
      })(_.unbind())
    }
}
