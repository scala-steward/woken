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

package ch.chuv.lren.woken.core

import java.util.UUID

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestKit, TestProbe }
import ch.chuv.lren.woken.config.AlgorithmsConfiguration
import ch.chuv.lren.woken.core.model.{ ErrorJobResult, JobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.util.{ FakeCoordinatorConfig, JsonUtils }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{ Failure, Success }

/**
  * Experiment flow should always complete with success, but the error is reported inside the response.
  */
class ExperimentFlowTest
    extends TestKit(ActorSystem("ExperimentFlowTest"))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with JsonUtils
    with LazyLogging {

  val config: Config =
    ConfigFactory
      .parseResourcesAnySyntax("algorithms.conf")
      .withFallback(ConfigFactory.load("test.conf"))
      .resolve()

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val user: UserId                             = UserId("test")

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_test1"), VariableId("college_math")),
      grouping = Nil,
      filters = None,
      targetTable = Some("sample_data"),
      algorithms = List(AlgorithmSpec(algorithm, parameters)),
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    ConfigUtil.lift(
      ExperimentActor.Job(
        jobId = UUID.randomUUID().toString,
        inputDb = "featuresDb",
        inputTable = query.targetTable.getOrElse("sample_data"),
        query = query,
        metadata = Nil
      )
    )

  import ExperimentFlowWrapper._

  "Experiment flow" should {

    "complete with success in case of a query with no algorithms" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props)
      val experimentQuery = ExperimentQuery(
        user = user,
        variables = Nil,
        covariables = Nil,
        grouping = Nil,
        filters = None,
        targetTable = None,
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val experimentJob = experimentQuery2job(experimentQuery)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.isEmpty shouldBe true
      }

    }

    "complete with success in case of a query containing an invalid algorithm" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props)
      val experiment    = experimentQuery("invalid-algo", Nil)
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("invalid-algo", Nil)
          response.result.head._2 match {
            case ejr: ErrorJobResult =>
              ejr.error shouldBe "Could not find key: algorithms.invalid-algo"
            case _ => fail("Response should be of type ErrorJobResponse")
          }
      }

    }

    "complete with success in case of a query containing a failing algorithm" in {
      val experimentWrapper =
        system.actorOf(ExperimentWithFailingAlgoFlowWrapper.props)
      val experiment    = experimentQuery("knn", Nil)
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          print(response)
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("knn", Nil)
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error.nonEmpty shouldBe true
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }

    }

    "complete with success in case of a valid query" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props)

      val experiment = experimentQuery("knn", List(CodeValue("k", "5")))

      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, experimentJob.toOption.get)
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: ExperimentResponse =>
          response.result.nonEmpty shouldBe true
          response.result.head._1 shouldBe AlgorithmSpec("knn", List(CodeValue("k", "5")))
          response.result.head._2 match {
            case ejr: ErrorJobResult => ejr.error.nonEmpty shouldBe true
            case _                   => fail("Response should be of type ErrorJobResponse")
          }
      }
    }

    "split flow should return validation failed" in {
      val experimentWrapper =
        system.actorOf(ExperimentFlowWrapper.props, "SplitFlowProbeActor")
      val experimentQuery = ExperimentQuery(
        user = user,
        variables = Nil,
        covariables = Nil,
        grouping = Nil,
        filters = None,
        targetTable = None,
        trainingDatasets = Set(),
        testingDatasets = Set(),
        validationDatasets = Set(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val experimentJob = experimentQuery2job(experimentQuery)
      experimentJob.isValid shouldBe true
      val testProbe = TestProbe()
      testProbe.send(experimentWrapper, SplitFlowCommand(experimentJob.toOption.get))
      testProbe.expectMsgPF(20 seconds, "error") {
        case response: SplitFlowResponse =>
          response.algorithmMaybe.isDefined shouldBe true
      }
    }

  }

  object ExperimentFlowWrapper {
    def props = Props(new ExperimentFlowWrapper)

    case class ExperimentResponse(result: Map[AlgorithmSpec, JobResult])

    case class SplitFlowCommand(job: ExperimentActor.Job)

    case class SplitFlowResponse(algorithmMaybe: Option[AlgorithmValidationMaybe])

  }

  /**
    * This actor provides the environment for executing experimentFlow
    */
  class ExperimentFlowWrapper extends Actor {

    private val coordinatorActor  = context.actorOf(FakeCoordinatorActor.props)
    private val coordinatorConfig = FakeCoordinatorConfig.coordinatorConfig(coordinatorActor)
    private val algorithmLookup   = AlgorithmsConfiguration.factory(config)
    val experimentFlow = ExperimentFlow(
      FakeCoordinatorActor.executeFailingJobAsync("Algorithm failure"),
      coordinatorConfig.featuresDatabase,
      "testNode",
      algorithmLookup,
      context
    )

    override def receive: Receive = {
      case job: ExperimentActor.Job =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.flow)
          .runWith(TestSink.probe[Map[AlgorithmSpec, JobResult]])
          .request(1)
          .receiveWithin(10 seconds, 1)
        originator ! ExperimentResponse(result.headOption.getOrElse(Map.empty))

      case SplitFlowCommand(job) =>
        val originator = sender()
        val result = Source
          .single(job)
          .via(experimentFlow.splitJob)
          .runWith(TestSink.probe[AlgorithmValidationMaybe])
          .request(1)
          .receiveWithin(10 seconds, 1)
        originator ! SplitFlowResponse(result.headOption)
    }
  }

  object ExperimentWithFailingAlgoFlowWrapper {
    def props = Props(new ExperimentWithFailingAlgoFlowWrapper)
  }

  /**
    * This actor provides the environment for executing experimentFlow where a failing algorithm is simulated by
    * a coordinator actor returning failures.
    */
  class ExperimentWithFailingAlgoFlowWrapper extends Actor {

    private val coordinatorActor  = testActor
    private val coordinatorConfig = FakeCoordinatorConfig.coordinatorConfig(coordinatorActor)
    private val algorithmLookup   = AlgorithmsConfiguration.factory(config)
    val experimentFlow = ExperimentFlow(
      FakeCoordinatorActor.executeFailingJobAsync("Algorithm failure"),
      coordinatorConfig.featuresDatabase,
      "testNode",
      algorithmLookup,
      context
    )

    override def receive: Receive = {
      case job: ExperimentActor.Job =>
        val originator = sender()
        Source
          .single(job)
          .via(experimentFlow.flow)
          .runWith(Sink.last)
          .onComplete {
            case Success(result)        => originator ! ExperimentResponse(result)
            case Failure(ex: Throwable) => logger.error("Failed", ex)
          }

      //.runWith(TestSink.probe[Map[AlgorithmSpec, JobResult]])
      //request(1)
      //.receiveWithin(10 seconds, 1)
    }
  }

}