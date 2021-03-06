package akka.stream.io

import java.net.{ InetAddress, NetworkInterface }

import akka.actor._
import akka.stream.io.InterfaceMonitor._
import akka.util.ByteString

import scala.collection.JavaConversions.enumerationAsScalaIterator
import scala.collection.JavaConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.language.postfixOps
import scala.reflect.ClassTag

object InterfaceMonitor {

  def props(interval: FiniteDuration): Props = Props.apply(new InterfaceMonitor(interval))

  sealed trait InterfaceMonitorApi

  sealed trait InterfaceStateEvent

  final case class Register(listener: ActorRef) extends InterfaceMonitorApi

  final case class UnRegister(listener: ActorRef) extends InterfaceMonitorApi

  final case class Delta(myKnowledge: Set[NetworkInterfaceRepr])

  final case class NetworkInterfaceRepr(name: String, hardwareAddress: ByteString, address: InetAddress, prefixLength: Short)

  final case class IfUp(interface: NetworkInterfaceRepr) extends InterfaceStateEvent
  final case class IfDown(interface: NetworkInterfaceRepr) extends InterfaceStateEvent
  final case class DeltaResponse(ifups: Seq[NetworkInterfaceRepr], ifDowns: Seq[NetworkInterfaceRepr]) extends InterfaceStateEvent

}

class InterfaceMonitor private[io] (interval: FiniteDuration) extends Actor with ActorLogging {

  import context.dispatcher
  val tick = context.system.scheduler.schedule(Duration.Zero, interval, self, Tick)
  var knownInterfaces: Set[NetworkInterfaceRepr] = Set.empty
  var listeners: Set[ActorRef] = Set.empty
  updateKnownInterfaces()

  def specialized[T: ClassTag](f: PartialFunction[T, Unit]): PartialFunction[Any, Unit] = {
    case t: T if f.isDefinedAt(t) => f(t)
    case _ =>
  }

  def fallback(f: PartialFunction[Any, Unit]): PartialFunction[Any, Unit] = f

  override def receive: Receive = {
    case Register(listener) =>
      context.watch(listener)
      listeners += listener
    case UnRegister(listener) =>
      context.unwatch(listener)
      listeners -= listener
    case Delta(oldKnowledge) =>
      sender() ! computeChanges(oldKnowledge, knownInterfaces)
    case Terminated(listener) => listeners -= listener
    case Tick => updateKnownInterfaces()
    case _ =>
  }

  private def updateKnownInterfaces() = {
    val currentInterfaces = enumerationAsScalaIterator(NetworkInterface.getNetworkInterfaces).toList
      .flatMap(f => f :: enumerationAsScalaIterator(f.getSubInterfaces).toList)
      .filter(_.isUp)
      .flatMap(i => i.getInterfaceAddresses.asScala.map(a => (i, a)))
      .map { case (i, a) => NetworkInterfaceRepr(i.getName, Option(i.getHardwareAddress).map(ByteString.apply).getOrElse(ByteString.empty), a.getAddress, a.getNetworkPrefixLength) }.toSet

    val changes = computeChanges(knownInterfaces, currentInterfaces)
    listeners.foreach(_ ! changes)
    knownInterfaces = currentInterfaces
  }

  private def computeChanges(old: Set[NetworkInterfaceRepr], current: Set[NetworkInterfaceRepr]) =
    DeltaResponse((current -- old).toSeq, (old -- current).toSeq)

  override def postStop(): Unit = tick.cancel()

  object Tick
}
