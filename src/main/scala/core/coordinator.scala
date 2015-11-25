package core

import akka.actor.FSM.Failure
import akka.actor._
import api.JobDto
import core.CoordinatorActor.Start
import core.clients.{JobClientService, ChronosService}
import core.model.JobToChronos
import core.model.JobResult
import dao.DAL
import models.ChronosJob
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{JsonFormat, DefaultJsonProtocol}

import scala.concurrent.duration._

/**
 * We use the companion object to hold all the messages that the ``CoordinatorActor``
 * receives.
 */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: JobDto) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[Start] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  type WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobComplete = JobClientService.JobComplete
  val WorkerJobError = JobClientService.JobError

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  type Result = core.model.JobResult
  val Result = core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[ErrorResponse] = ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(jsonFormat1(ErrorResponse))
  }

  import JobResult._
  implicit val resultFormat: JsonFormat[Result] = JobResult.jobResultFormat
  implicit val errorResponseFormat = jsonFormat1(ErrorResponse.apply)

  def props(chronosService: ActorRef, resultDatabase: DAL, federationDatabase: Option[DAL]): Props =
    federationDatabase.map(fd => Props(classOf[FederationCoordinatorActor], chronosService, resultDatabase, fd))
      .getOrElse(Props(classOf[LocalCoordinatorActor], chronosService, resultDatabase))

}

sealed trait State
case object WaitForNewJob extends State
case object WaitForChronos extends State
case object WaitForNodes extends State
case object RequestFinalResult extends State
case object RequestIntermediateResults extends State

trait StateData {
  def job: JobDto
}
case object Uninitialized extends StateData {
  def job = throw new IllegalAccessException()
}
case class WaitingForNodesData(job: JobDto, replyTo: ActorRef, remainingNodes: Set[String] = Set(), totalNodeCount: Int) extends StateData
case class WaitLocalData(job: JobDto, replyTo: ActorRef) extends StateData

/**
 * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
 *
 * This actor will have the responsibility of making two requests and then aggregating them together:
 *  - One request to Chronos to start the job
 *  - Then a separate request in the database for the results, repeated until enough results are present
 */
trait CoordinatorActor extends Actor with ActorLogging with LoggingFSM[State, StateData] {

  val repeatDuration = 200.milliseconds

  def chronosService: ActorRef
  def resultDatabase: DAL

  startWith(WaitForNewJob, Uninitialized)

  when (WaitForChronos) {
    case Event(Ok, data: WaitLocalData) => goto(RequestFinalResult) using data
    case Event(e: Error, data: WaitLocalData) =>
      val msg: String = e.message
      data.replyTo ! Error(msg)
      stop(Failure(msg))
    case Event(e: Timeout @unchecked, data: WaitLocalData) =>
      val msg: String = "Timeout while connecting to Chronos"
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  when (RequestFinalResult, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitLocalData) => {
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        data.replyTo ! PutJobResults(results)
        stop()
      } else {
        stay() forMax repeatDuration
      }
    }
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("Received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }


  def transitions: TransitionHandler = {

    case _ -> WaitForChronos =>
      import ChronosService._
      val chronosJob: ChronosJob = JobToChronos.enrich(nextStateData.job)
      chronosService ! Schedule(chronosJob)

  }

  onTransition( transitions )

}

class LocalCoordinatorActor(val chronosService: ActorRef, val resultDatabase: DAL) extends CoordinatorActor {
  log.info ("Local coordinator actor started...")

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      goto(WaitForChronos) using WaitLocalData(job, sender())
    }
  }

  when (WaitForNodes) {
    case _ => stop(Failure("Unexpected state WaitForNodes"))
  }

  when (RequestIntermediateResults) {
    case _ => stop(Failure("Unexpected state RequestIntermediateResults"))
  }

  initialize()

}

class FederationCoordinatorActor(val chronosService: ActorRef, val resultDatabase: DAL, val federationDatabase: DAL) extends CoordinatorActor {

  import CoordinatorActor._

  when (WaitForNewJob) {
    case Event(Start(job), data: StateData) => {
      import config.Config
      val replyTo = sender()
      val nodes = job.nodes.filter(_.isEmpty).getOrElse(Config.jobs.nodes)

      log.warning(s"List of nodes: ${nodes.mkString(",")}")

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = None))
        }
        goto(WaitForNodes) using WaitingForNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(WaitForChronos) using WaitLocalData(job, replyTo)
      }
    }
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when (WaitForNodes) {
    case Event(WorkerJobComplete(node), data: WaitingForNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    case Event(WorkerJobError(node, message), data: WaitingForNodesData) => {
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
    }
  }

  when (RequestIntermediateResults, stateTimeout = repeatDuration) {
    case Event(StateTimeout, data: WaitingForNodesData) => {
      val results = federationDatabase.findJobResults(data.job.jobId)
      if (results.size == data.totalNodeCount) {
        data.job.federationDockerImage.fold {
          data.replyTo ! PutJobResults(results)
          stop()
        } { federationDockerImage =>
          goto(WaitForChronos) using WaitLocalData(data.job.copy(dockerImage = federationDockerImage), data.replyTo)
        }
      } else {
        stay() forMax repeatDuration
      }
    }
  }

  initialize()

}