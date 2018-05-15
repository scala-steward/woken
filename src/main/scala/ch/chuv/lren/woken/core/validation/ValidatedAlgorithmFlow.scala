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

package ch.chuv.lren.woken.core.validation

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.{ ActorContext, ActorSystem }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Sink, Source, Zip, ZipWith }
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import ch.chuv.lren.woken.core.CoordinatorActor
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.{ Score, validationProtocol }
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.service.DispatcherService
import spray.json._
import validationProtocol._

import scala.concurrent.ExecutionContext

object ValidatedAlgorithmFlow {

  case class Job(jobId: String,
                 inputDb: String,
                 inputTable: String,
                 query: MiningQuery,
                 metadata: List[VariableMetaData],
                 validations: List[ValidationSpec],
                 remoteValidationDatasets: Set[DatasetId],
                 algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)

    if (!algorithmDefinition.predictive) {
      assert(validations.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, Score]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: JobResult)

}

case class ValidatedAlgorithmFlow(
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    jobsConf: JobsConfiguration,
    dispatcherService: DispatcherService,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  import ValidatedAlgorithmFlow._

  private val log = Logging(context.system, getClass)

  private val crossValidationFlow = CrossValidationFlow(executeJobAsync, featuresDatabase, context)

  /**
    * Run a predictive and local algorithm and perform its validation procedure.
    *
    * If the algorithm is predictive, validate it using cross-validation for validation with local data
    * and if the algorithm is not distributed, validate using remote datasets if any.
    *
    * @param parallelism Parallelism factor
    * @return A flow that executes an algorithm and its validation procedures
    */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def runLocalAlgorithmAndValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ResultResponse, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        def mergeValidations(respWithRemoteValidations: (CoordinatorActor.Response,
                                                         ValidationResults),
                             crossValidations: ValidationResults) =
          (respWithRemoteValidations._1, respWithRemoteValidations._2 ++ crossValidations)

        // prepare graph elements
        val broadcast = builder.add(Broadcast[ValidatedAlgorithmFlow.Job](2))
        val zip       = builder.add(ZipWith(mergeValidations _))
        val response  = builder.add(buildResponse)

        // connect the graph
        broadcast.out(0) ~> runAlgorithmOnLocalData ~> remoteValidate ~> zip.in0
        broadcast.out(1) ~> crossValidate(parallelism) ~> zip.in1
        zip.out ~> response

        FlowShape(broadcast.in, response.out)
      })
      .named("run-algorithm-and-validate")

  /**
    * Execute an algorithm and learn from the local data.
    *
    * @return
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def runAlgorithmOnLocalData: Flow[ValidatedAlgorithmFlow.Job,
                                    (ValidatedAlgorithmFlow.Job, CoordinatorActor.Response),
                                    NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .mapAsync(1) { job =>
        val algorithm = job.query.algorithm

        log.info(s"Start job for algorithm ${algorithm.code}")

        // Spawn a CoordinatorActor
        val jobId = UUID.randomUUID().toString
        val featuresQuery =
          job.query
            .filterNulls(job.algorithmDefinition.variablesCanBeNull,
                         job.algorithmDefinition.covariablesCanBeNull)
            .features(job.inputTable, None)
        val subJob =
          DockerJob(jobId,
                    job.algorithmDefinition.dockerImage,
                    job.inputDb,
                    featuresQuery,
                    job.query.algorithm,
                    job.metadata)
        executeJobAsync(subJob).map(response => (job, response))
      }
      .log("Learned from available local data")
      .named("learn-from-available-local-data")

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def remoteValidate: Flow[(ValidatedAlgorithmFlow.Job, CoordinatorActor.Response),
                                   (CoordinatorActor.Response, ValidationResults),
                                   NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val broadcast =
          builder.add(Broadcast[(ValidatedAlgorithmFlow.Job, CoordinatorActor.Response)](2))
        val zip = builder.add(Zip[CoordinatorActor.Response, ValidationResults]())

        // connect the graph
        broadcast.out(0).map(_._2) ~> zip.in0
        broadcast.out(1) ~> dispatchRemoteValidations ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      })
      .log("Remote validation results")
      .named("remote-validate")

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Any",
      "org.wartremover.warts.IsInstanceOf",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable",
      "org.wartremover.warts.Throw"
    )
  )
  private def dispatchRemoteValidations: Flow[
    (ValidatedAlgorithmFlow.Job, CoordinatorActor.Response),
    ValidationResults,
    NotUsed
  ] =
    Flow[(ValidatedAlgorithmFlow.Job, CoordinatorActor.Response)]
      .mapAsync(1) {
        case (job, response) =>
          response.results
            .find(r => r.isInstanceOf[PfaJobResult])
            .fold(
              throw new IllegalArgumentException(
                "Expected one PFA result in the results of a predictive algorithm"
              )
            ) {
              case r: PfaJobResult =>
                val query = job.query.copy(
                  algorithm = AlgorithmSpec(
                    ValidationJob.algorithmCode,
                    List(
                      CodeValue("model", r.model.compactPrint),
                      CodeValue("variablesCanBeNull",
                                job.algorithmDefinition.variablesCanBeNull.toString),
                      CodeValue("covariablesCanBeNull",
                                job.algorithmDefinition.covariablesCanBeNull.toString)
                    )
                  ),
                  executionPlan = None,
                  datasets = job.remoteValidationDatasets
                )
                log.info(s"Prepared remote validation query: $query")

                // It's ok to drop remote validations that failed, there can be network errors
                // Future alternative: use recover and inject a QueryResult with error, problem is we cannot know
                // which node caused the error
                val decider: Supervision.Decider = {
                  case e: Exception =>
                    log.warning(s"Could not dispatch remote validation query, $e")
                    Supervision.Resume
                  case _ => Supervision.Stop
                }
                implicit val system: ActorSystem = context.system
                implicit val materializer: Materializer = ActorMaterializer(
                  ActorMaterializerSettings(system).withSupervisionStrategy(decider)
                )
                Source
                  .single(query)
                  .via(dispatcherService.dispatchRemoteMiningFlow)
                  .map(_._2)
                  .log("Remote validations")
                  .runWith(Sink.seq[QueryResult])
              case other => throw new IllegalArgumentException(s"Unexpected result $other")

            }
      }
      .map[ValidationResults] { l =>
        l.map {
          case QueryResult(_, node, _, shape, _, Some(data), None) if shape == Shapes.score =>
            // Rebuild the spec from the results
            val spec = ValidationSpec("remote-validation", List(CodeValue("node", node)))
            (spec, Right[String, Score](data.convertTo[Score]))
          case QueryResult(_, node, _, shape, _, None, Some(error)) if shape == Shapes.error =>
            val spec = ValidationSpec("remote-validation", List(CodeValue("node", node)))
            (spec, Left[String, Score](error))
          case otherResult =>
            log.error(s"Unhandled validation result: $otherResult")
            val spec =
              ValidationSpec("remote-validation", List(CodeValue("node", otherResult.node)))
            (spec, Left[String, Score](s"Unhandled result of shape ${otherResult.`type`}"))
        }.toMap

      }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def crossValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ValidationResults, NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .map { job =>
        job.validations.map { v =>
          val jobId = UUID.randomUUID().toString
          CrossValidationFlow.Job(jobId,
                                  job.inputDb,
                                  job.inputTable,
                                  job.query,
                                  job.metadata,
                                  v,
                                  job.algorithmDefinition)
        }
      }
      .mapConcat(identity)
      .via(crossValidationFlow.crossValidate(parallelism))
      .map(_.map(t => t._1.validation -> t._2))
      .fold[Map[ValidationSpec, Either[String, Score]]](Map()) { (m, rOpt) =>
        rOpt.fold(m) { r =>
          m + r
        }
      }
      .log("Cross validation results")
      .named("cross-validate")

  private def nodeOf(spec: ValidationSpec): Option[String] =
    spec.parameters.find(_.code == "node").map(_.value)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def buildResponse
    : Flow[(CoordinatorActor.Response, ValidationResults), ResultResponse, NotUsed] =
    Flow[(CoordinatorActor.Response, ValidationResults)]
      .map {
        case (response, validations) =>
          val validationsJson = JsArray(
            validations
              .map({
                case (key, Right(value)) =>
                  JsObject("code" -> JsString(key.code),
                           "node" -> JsString(nodeOf(key).getOrElse(jobsConf.node)),
                           "data" -> value.toJson)
                case (key, Left(message)) =>
                  JsObject("code"  -> JsString(key.code),
                           "node"  -> JsString(nodeOf(key).getOrElse(jobsConf.node)),
                           "error" -> JsString(message))
              })
              .toVector
          )

          val algorithm = response.job.algorithmSpec
          response.results.headOption match {
            case Some(pfa: PfaJobResult) =>
              val model = pfa.injectCell("validations", validationsJson)
              ResultResponse(algorithm, model)
            case Some(model) =>
              ResultResponse(algorithm, model)
            case None =>
              ResultResponse(algorithm,
                             ErrorJobResult(Some(response.job.jobId),
                                            node = jobsConf.node,
                                            OffsetDateTime.now(),
                                            Some(algorithm.code),
                                            "No results"))
          }
      }
      .log("Response")
      .named("build-response")
}
