package mesosphere.util

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

object PortAllocator extends StrictLogging {

  // https://en.wikipedia.org/wiki/Ephemeral_port
  // The Internet Assigned Numbers Authority (IANA) suggests the range 49152 to 65535  for dynamic or private ports.
  val PORT_MAX = 65535
  val PORT_START = 49152
  val portCounter: AtomicInteger = new AtomicInteger(PORT_START)

  // Make sure the same ephemeral port is not given out twice making port collisions less likely. We're iterating
  // over ephemeral port range trying to open a socket and if successful return the port number. Should we ever run
  // out of free ephemeral ports a RuntimeException is thrown.
  @tailrec
  def freeSocket(): ServerSocket = {
    val port = portCounter.incrementAndGet()
    if (port > PORT_MAX) throw new RuntimeException("Out of ephemeral ports. Sorry")
    Try(new ServerSocket(port)) match {
      case Success(v) => v
      case Failure(ex) =>
        logger.warn(s"Failed to provide an ephemeral port because of ${ex.getMessage}. Will retry again...")
        freeSocket()
    }
  }

  def closeSocket(socket: ServerSocket) = {
    try { socket.close() }
    catch { case NonFatal(ex) => logger.debug(s"Failed to close port allocator's socket because ${ex.getMessage}") }
  }

  def ephemeralPort(): Int = {

    val socket = freeSocket()
    closeSocket(socket)
    return socket.getLocalPort
  }

}
