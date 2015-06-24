package akka.rtcweb.protocol.ice

import java.net.{ InetAddress, InetSocketAddress, NetworkInterface }

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.io.Udp
import akka.rtcweb.protocol.ice.IceAgent.{ GatherCandidates, OnIceCandidate }
import akka.rtcweb.protocol.ice.stun.{ Method, StunMessage, Class }
import akka.rtcweb.protocol.jsep.RTCPeerConnection.StunServerDescription
import akka.stream.io.InterfaceMonitorExtension
import akka.util.ByteString
import scodec.Attempt.{ Failure, Successful }
import scodec.DecodeResult
import scodec.bits.{ ByteVector }

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.forkjoin.ThreadLocalRandom

object IceAgent {

  def props(listener: ActorRef, iceServers: Vector[StunServerDescription]) = Props(new IceAgent(listener, iceServers))

  sealed trait Candidate { def address: InetSocketAddress; def base: HostCandidate }

  sealed trait CandidateType

  final case class OnIceCandidate(candidates: Seq[InetSocketAddress])

  final case class HostCandidate(address: InetSocketAddress) extends Candidate { override def base: HostCandidate = this }

  final case class ServerReflexiveCandidate(address: InetSocketAddress, base: HostCandidate) extends Candidate

  final case class PeerReflexiveCandidate(address: InetSocketAddress, base: HostCandidate) extends Candidate
  object CandidateType {
    case object HostCandidate extends CandidateType
    case object ServerReflexiveCandidate extends CandidateType
    case object PeerReflexiveCandidate extends CandidateType
    @deprecated("TURN not supported yet", "0.1") case object RelayedCandidate extends CandidateType
  }

  object GatherCandidates

}

class IceAgent private[ice] (listener: ActorRef, iceServers: Vector[StunServerDescription]) extends Actor with ActorLogging {

  InterfaceMonitorExtension(context.system).register
  require(iceServers.nonEmpty, "iceServers must not be empty")

  var outstandingTransactionIds: Vector[(ByteVector, StunServerDescription)] = Vector.empty

  def priority()(candidate: Candidate) = {

  }

  override def receive: Receive = {
    case Udp.Bound(localWildcardAddress) if localWildcardAddress.getAddress.isAnyLocalAddress =>
      context.become(ready(sender(), localAddresses(), localWildcardAddress.getPort))
    case Udp.Bound(localAddress) =>
      context.become(ready(sender(), Vector(localAddress.getAddress), localAddress.getPort))
  }

  def ready(socket: ActorRef, localAddresses: Seq[InetAddress], port: Int): Receive = {
    case Udp.Received(data, sender) =>
      val decoded = StunMessage.codec.complete.decode(ByteVector.view(data.asByteBuffer).bits)
      decoded match {
        case Successful(DecodeResult(StunMessage(Class.successResponse, Method.Binding, transactionId, attributes), _)) =>
          log.info(s"Received a decoded stun message: $transactionId with attributes $attributes")
        case Successful(a) => log.warning(s"unexpected reply: $a")
        case Failure(f) => log.warning(f.messageWithContext)
      }

    case GatherCandidates =>
      listener ! OnIceCandidate(localAddresses.map(address => new InetSocketAddress(address, port)))
      iceServers.foreach { server =>
        val transactionId = mkTransactionId()
        val stunBindingRequest = StunMessage(stun.Class.request, stun.Method.Binding, transactionId)
        val byteBuffer = ByteString.fromByteBuffer(StunMessage.codec.encode(stunBindingRequest).require.toByteBuffer).compact
        socket ! Udp.Send(byteBuffer, server.address)
        outstandingTransactionIds = outstandingTransactionIds :+ ((transactionId, server))
      }
  }

  private def mkTransactionId(): ByteVector = {
    val buffer = new Array[Byte](12)
    ThreadLocalRandom.current().nextBytes(buffer)
    ByteVector(buffer)
  }

  private def localAddresses(): Seq[InetAddress] = NetworkInterface.getNetworkInterfaces.
    toSeq.flatMap(_.getInetAddresses.toSeq).distinct.toVector

}
