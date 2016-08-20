package io.amient.akkahttp.actor

import java.util.{Properties, UUID}

import akka.actor.{Actor, ActorPath, ActorRef, Props, Status}
import akka.event.Logging
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{HttpRequest, _}
import akka.pattern.ask
import akka.routing._
import akka.util.Timeout
import io.amient.akkahttp.Coordinator
import io.amient.akkahttp.actor.Partition.{CollectUserInput, KillNode, ShowIndex, TestError}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object Gateway {
  final val CONFIG_AKKA_HOST = "gateway.akka.host"
  final val CONFIG_AKKA_PORT = "gateway.akka.port"
  final val CONFIG_NUM_PARTITIONS = "num.partitions"
  final val CONFIG_PARTITION_LIST = "partition.list"
}

class Gateway(appConfig: Properties) extends Actor {

  //TODO gateway must be able to route before the local partitions are online
  //TODO after the local partitions are online they must tell it to the coordinator
  //which then updates all other gateway nodes that are online

  import Gateway._

  val host = appConfig.getProperty(CONFIG_AKKA_HOST, "localhost")
  val akkaPort = appConfig.getProperty(CONFIG_AKKA_PORT, "2552").toInt
  val numPartitions = appConfig.getProperty(CONFIG_NUM_PARTITIONS).toInt
  val partitionList = appConfig.getProperty(CONFIG_PARTITION_LIST).split("\\,").map(_.toInt).toList

  val coordinator = Coordinator.fromProperties(appConfig)

  val log = Logging.getLogger(context.system, this)

  val uuid = UUID.randomUUID()

  val partitioner = context.actorOf(PartitionedGroup(numPartitions).props(), name = "partitioner")

  //construct partition actors
  val partitions = for (p <- partitionList) yield {
    context.actorOf(Props(new Partition(p)), name = "partition-" + p)
  }

  //create coorinator and register the partitions
  coordinator.watchRoutees(context.system, self)

  import context.dispatcher

  //register partitions that have booted successfully
  val handles = for (p <- partitions) yield {
    val partitionActorPath = ActorPath.fromString(
      s"akka.tcp://${context.system.name}@${host}:${akkaPort}${p.path.toStringWithoutAddress}")
    implicit val timeout = Timeout(24 hours)
    (for (partitionActorRef <- context.actorSelection(partitionActorPath).resolveOne()) yield {
      coordinator.register(partitionActorPath)
    }) recover {
      case any => throw new RuntimeException(any)
    }
  }

  override val supervisorStrategy = null

  override def preStart(): Unit = {
    log.info("Gateway Starting")
  }

  override def postStop(): Unit = {
    Await.result(Future.sequence(handles), 1 minute).foreach(coordinator.unregister)
    coordinator.close()
    super.postStop()
  }

  def receive = {
    case HttpRequest(GET, Uri.Path("/error"), _, _, _) =>
      implicit val timeout = Timeout(1 second)
      forwardAndHandleErrors(partitioner ? TestError, sender) {
        case x => sys.error("Expecting failure, got" + x)
      }

    //query path (GET)
    case HttpRequest(GET, uri, _, _, _) => uri match {

      case Uri.Path("/") | Uri.Path("") =>
        implicit val timeout = Timeout(1 second)
        forwardAndHandleErrors(partitioner ? ShowIndex(uri.queryString().getOrElse("")), sender) {
        case any =>  HttpResponse(
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>" + any + "</h1>"))

        }

      case Uri.Path("/kill") =>
        implicit val timeout = Timeout(1 second)
        uri.query() match {
          case Seq(("p", x)) if (x.toInt >= 0) =>
            forwardAndHandleErrors(partitioner ? KillNode(x.toInt), sender) {
              case any => HttpResponse(status = StatusCodes.Accepted)
            }
          case _ => sender ! badRequest(sender, "query string param p must be >=0")
        }

      case Uri.Path("/hello") =>
        implicit val timeout = Timeout(60 seconds)
        forwardAndHandleErrors(partitioner ? CollectUserInput(uri.queryString().getOrElse(null)), sender) {
          case justText: String => HttpResponse(
            entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>" + justText + "</h1>"))

          case s => HttpResponse(status = StatusCodes.NotAcceptable,
            entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Can't give you that: " + s.getClass + "</h1>"))
        }

      case Uri.Path("/hi") =>
        sender ! HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
          "<html><body><h2>Please wait..</h2>" +
            "<script>setTimeout(\"location.href = '/hello?" + uri.query() + "';\",100);</script></body></html>"))

      case _ =>
        sender ! HttpResponse(status = StatusCodes.NotFound,
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Haven't got that</h1>"))
    }


    //TODO command path (POST)


    case HttpRequest(_, _, _, _, _) =>
      sender ! HttpResponse(status = StatusCodes.MethodNotAllowed,
        entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Can't do that here </h1>"))

    //Management queries
    case m: AddRoutee =>
      log.info("Adding partition: " + m.routee)
      partitioner ! m

    case m: RemoveRoutee =>
      log.info("Removing partition: " + m.routee)
      partitioner ! m

    case m: GetRoutees =>
      val origin = sender()
      implicit val timeout = Timeout(60 seconds)
      partitioner ? m onSuccess { case routees => origin ! routees }

    case _ => sender ! Status.Failure(new IllegalArgumentException)

  }

  def badRequest(replyTo: ActorRef, message: String) = {
    replyTo ! HttpResponse(status = StatusCodes.BadRequest,
      entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>$message</h1>"))
  }

  def forwardAndHandleErrors(future: Future[Any], replyTo: ActorRef)(f: Any => HttpResponse) = {
    future andThen {
      case Success(result) =>
        replyTo ! f(result)

      case Failure(e: IllegalArgumentException) =>
        log.error("Gateway contains bug! ", e)
        replyTo ! HttpResponse(status = StatusCodes.InternalServerError,
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1> Eeek! We have a bug..</h1>"))

      case Failure(NonFatal(e)) =>
        log.error("Cluster encountered failure ", e)
        replyTo ! HttpResponse(status = StatusCodes.InternalServerError,
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
            "<h1> Well, something went wrong but we should be back..</h1>"))

      case Failure(e) =>
        replyTo ! HttpResponse(status = StatusCodes.InternalServerError,
          entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
            "<h1> Something is seriously wrong with our servers..</h1>"))
    }
  }

}
