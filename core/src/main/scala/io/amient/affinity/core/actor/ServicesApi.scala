package io.amient.affinity.core.actor

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorRef, Props}
import akka.event.Logging
import akka.pattern.ask
import akka.routing._
import akka.serialization.SerializationExtension
import akka.util.Timeout
import io.amient.affinity.core.ack
import io.amient.affinity.core.actor.Controller.{CreateGateway, GracefulShutdown}
import io.amient.affinity.core.actor.Service.{CheckServiceAvailability, ServiceAvailability}
import io.amient.affinity.core.cluster.Coordinator
import io.amient.affinity.core.cluster.Coordinator.MasterStatusUpdate
import io.amient.affinity.core.config.{Cfg, CfgGroup, CfgStruct}
import io.amient.affinity.core.serde.avro.AvroSerdeProxy

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object ServicesApi {

  class Conf extends CfgStruct[Conf](Cfg.Options.IGNORE_UNKNOWN) {
    val Gateway = struct("affinity.node.gateway", new GatewayConf, false)
    val Services = group("affinity.service", new ServicesConf, false)
  }

  class ServicesConf extends CfgGroup(classOf[Service.Config])

  class GatewayConf extends CfgStruct[GatewayConf] {
    val Class = cls("class", classOf[ServicesApi], false)
    val SuspendQueueMaxSize = integer("suspend.queue.max.size", 1000)
    val Http = struct("http", new GatewayHttp.HttpConf, false)
  }


  final case class GatewayClusterStatus(suspended: Boolean)
}

trait ServicesApi extends ActorHandler {

  private val log = Logging.getLogger(context.system, this)

  import ServicesApi._

  private val conf = new ServicesApi.Conf()(context.system.settings.config)

  if (conf.Gateway.Http.isDefined && !classOf[GatewayHttp].isAssignableFrom(this.getClass)) {
    log.warning("affinity.gateway.http interface is configured but the node is trying " +
      "to instantiate a non-http gateway. This may lead to uncertainity in the Controller.")
  }

  private implicit val scheduler = context.system.scheduler

  import context.dispatcher

  def onClusterStatus(suspended: Boolean): Unit = ()

  private val services = mutable.Map[String, (Coordinator, ActorRef, AtomicBoolean)]()
  private var handlingSuspended = true

  def service(group: String): ActorRef = {
    services.get(group) match {
      case Some(coordinatedService) => coordinatedService._2
      case None =>
        if (!handlingSuspended) throw new IllegalStateException("All required affinity services must be declared in the constructor")
        val serviceConfig = conf.Services(group).config()
        val service = context.actorOf(Props(new Service(serviceConfig)), name = group)
        val coordinator = Coordinator.create(context.system, group)
        context.watch(service)
        services += (group -> (coordinator, service, new AtomicBoolean(true)))
        service
    }

  }

  private def checkClusterStatus(msg: Option[ServiceAvailability] = None): Unit = {
    val gatewayShouldBeSuspended = services.exists(_._2._3.get)
    if (gatewayShouldBeSuspended != handlingSuspended) {
      handlingSuspended = gatewayShouldBeSuspended
      onClusterStatus(gatewayShouldBeSuspended)
      if (self.path.name == "gateway") {
        msg.foreach(context.system.eventStream.publish(_)) // this one is for IntegrationTestBase
        context.system.eventStream.publish(GatewayClusterStatus(gatewayShouldBeSuspended)) //this one for SystemTestBase
      }
    }
  }

  abstract override def preStart(): Unit = {
    super.preStart()
    checkClusterStatus()
    services.foreach {
      case(_, (coordinator, _, _)) => coordinator.watch(self, global = true)
    }
  }

  abstract override def postStop(): Unit = {
    super.postStop()
    services.values.foreach(_._1.unwatch(self))
  }

  abstract override def manage = super.manage orElse {

    case msg@CreateGateway if !classOf[GatewayHttp].isAssignableFrom(this.getClass) =>
      context.parent ! Controller.GatewayCreated(-1)

    case msg@MasterStatusUpdate(group, add, remove) => sender.reply(msg) {
      val service: ActorRef = services(group)._2
      remove.foreach(ref => service ! RemoveRoutee(ActorRefRoutee(ref)))
      add.foreach(ref => service ! AddRoutee(ActorRefRoutee(ref)))
      service ! CheckServiceAvailability(group)
    }

    case msg@ServiceAvailability(group, suspended) =>
      val (_, _, currentlySuspended) = services(group)
      if (currentlySuspended.get != suspended) {
        services(group)._3.set(suspended)
        checkClusterStatus(Some(msg))
      }

    case request@GracefulShutdown() => sender.reply(request) {
      context.stop(self)
    }

  }

  def describeAvro: scala.collection.immutable.Map[String, String] = {
    val serde = SerializationExtension(context.system).serializerByIdentity(200).asInstanceOf[AvroSerdeProxy].internal
    serde.describeSchemas.map {
      case (id, schema) => ((id.toString, schema.toString))
    }
  }

  def describeServices: scala.collection.immutable.Map[String, String] = {
    services.toMap.map { case (group, (_, actorRef, _)) => (group, actorRef.path.toString) }
  }

  def describeRegions: scala.collection.immutable.List[String] = {
    val t = 60 seconds
    implicit val timeout = Timeout(t)
    val routeeSets = Future.sequence {
      services.toMap.map { case (group, (_, actorRef, _)) =>
        actorRef ? GetRoutees map (_.asInstanceOf[Routees])
      }
    }
    Await.result(routeeSets, t).map(_.routees).flatten.map(_.toString).toList.sorted
  }



}
