package mesosphere.util

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object PortAllocator extends StrictLogging {

  // https://en.wikipedia.org/wiki/Ephemeral_port
  // The Internet Assigned Numbers Authority (IANA) suggests the range 49152 to 65535  for dynamic or private ports.
  // Many Linux kernels use the port range 32768 to 61000.

  // We reserve 1000 ephemeral ports for Marathon/Zk/Mesos Master/Mesos Agent processes and use the rest as port
  // resources for Mesos Agents e.g. in `MesosCluster`
  val EPHEMERAL_PORT_START = 32768
  val EPHEMERAL_PORT_MAX = 33768
  // IMPORTANT: Those ranges should NOT overlap
  val PORT_RANGE_START = 33769
  val PORT_RANGE_MAX = 65535

  // Default port range size, typically given out to mesos agents
  val DEFAULT_PORT_RANGE_SIZE = 100

  // We use 2 different atomic counters: one for ephemeral ports and one for port ranges.
  private val ephemeralPorts: AtomicInteger = new AtomicInteger(EPHEMERAL_PORT_START)
  private val rangePorts: AtomicInteger = new AtomicInteger(PORT_RANGE_START)

  // Make sure the same ephemeral port is not given out twice making port collisions less likely. We're iterating
  // over ephemeral port range trying to open a socket and if successful return the port number. Should we ever run
  // out of free ephemeral ports a RuntimeException is thrown.
  @tailrec
  private def freeSocket(): ServerSocket = {
    val port = ephemeralPorts.incrementAndGet()
    if (port > EPHEMERAL_PORT_MAX) throw new RuntimeException("Out of ephemeral ports.")
    Try(new ServerSocket(port)) match {
      case Success(v) => v
      case Failure(ex) =>
        logger.warn(s"Failed to provide an ephemeral port because of ${ex.getMessage}. Will retry again...")
        freeSocket()
    }
  }

  private def closeSocket(socket: ServerSocket) = {
    try { socket.close() }
    catch { case NonFatal(ex) => logger.debug(s"Failed to close port allocator's socket because ${ex.getMessage}") }
  }

  def ephemeralPort(): Int = {
    val socket = freeSocket()
    closeSocket(socket)
    socket.getLocalPort
  }

  def portsRange(step: Int = DEFAULT_PORT_RANGE_SIZE): (Int, Int) = {
    val from = rangePorts.getAndAdd(step + 1) // port resources in mesos are inclusive
    val to = from + step
    if (to > PORT_RANGE_MAX) throw new RuntimeException("Port range is depleted.")
    (from, to)
  }
}
