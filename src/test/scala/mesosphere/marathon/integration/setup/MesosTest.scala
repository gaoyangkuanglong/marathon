package mesosphere.marathon
package integration.setup

import java.io.File
import java.nio.file.{ Files, Paths }

import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.stream.Materializer
import mesosphere.marathon.integration.facades.MesosFacade
import mesosphere.marathon.util.Retry
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.async.Async._
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.sys.process.Process
import scala.util.{ Failure, Success, Try }

case class MesosConfig(
  launcher: String = "posix",
  containerizers: String = "mesos",
  isolation: Option[String] = None,
  imageProviders: Option[String] = None)

case class MesosCluster(
    suiteName: String,
    numMasters: Int,
    numSlaves: Int,
    masterUrl: String,
    quorumSize: Int = 1,
    autoStart: Boolean = false,
    config: MesosConfig = MesosConfig(),
    waitForLeaderTimeout: FiniteDuration = 5.minutes)(implicit
  system: ActorSystem,
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends AutoCloseable {
  require(quorumSize > 0 && quorumSize <= numMasters)

  lazy val masters = 0.until(numMasters).map { i =>
    Mesos(master = true, Seq(
      "--slave_ping_timeout=1secs",
      "--max_slave_ping_timeouts=4",
      s"--quorum=$quorumSize"))
  }

  lazy val agents = 0.until(numSlaves).map { i =>
    // uniquely identify each agent node, useful for constraint matching
    Mesos(master = false, Seq(
      s"--attributes=node:$i"
    ))
  }

  if (autoStart) {
    start()
  }

  def start(): Future[String] = {
    masters.foreach(_.start())
    agents.foreach(_.start())
    waitForLeader()
  }

  def waitForLeader(): Future[String] = async {
    val firstMaster = s"http://${masters.head.ip}:${masters.head.port}"
    val result = Retry("wait for leader", maxAttempts = Int.MaxValue, maxDelay = waitForLeaderTimeout) {
      Http().singleRequest(Get(firstMaster + "/redirect")).map { result =>
        result.discardEntityBytes() // forget about the body
        if (result.status.isFailure()) {
          throw new Exception(s"Couldn't determine leader: $result")
        }
        result
      }
    }

    def maybeFixURI(uri: String): String = {
      // some versions of mesos issue redirects with broken Location headers; fix them here
      if (uri.indexOf("//") == 0) {
        "http:" + uri
      } else {
        uri
      }
    }

    val location = await(result).headers.find(_.lowercaseName() == "location").map(_.value()).getOrElse(firstMaster)
    maybeFixURI(location)
  }

  def stop(): Unit = {
    masters.foreach(_.stop())
    agents.foreach(_.stop())
  }

  private def defaultContainerizers: String = {
    if (sys.env.getOrElse("RUN_DOCKER_INTEGRATION_TESTS", "true") == "true") {
      "docker,mesos"
    } else {
      "mesos"
    }
  }

  private def mesosEnv(mesosWorkDir: File): Seq[(String, String)] = {
    def write(dir: File, fileName: String, content: String): String = {
      val file = File.createTempFile(fileName, "", dir)
      file.deleteOnExit()
      FileUtils.write(file, content)
      file.setReadable(true)
      file.getAbsolutePath
    }

    val credentialsPath = write(mesosWorkDir, fileName = "credentials", content = "principal1 secret1")
    val aclsPath = write(mesosWorkDir, fileName = "acls.json", content =
      """
        |{
        |  "run_tasks": [{
        |    "principals": { "type": "ANY" },
        |    "users": { "type": "ANY" }
        |  }],
        |  "register_frameworks": [{
        |    "principals": { "type": "ANY" },
        |    "roles": { "type": "ANY" }
        |  }],
        |  "reserve_resources": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "resources": { "type": "ANY" }
        |  }],
        |  "create_volumes": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "volume_types": { "type": "ANY" }
        |  }]
        |}
      """.stripMargin)
    Seq(
      "MESOS_WORK_DIR" -> mesosWorkDir.getAbsolutePath,
      "MESOS_RUNTIME_DIR" -> new File(mesosWorkDir, "runtime").getAbsolutePath,
      "MESOS_LAUNCHER" -> "posix",
      "MESOS_CONTAINERIZERS" -> config.containerizers,
      "MESOS_LAUNCHER" -> config.launcher,
      "MESOS_ROLES" -> "public,foo",
      "MESOS_ACLS" -> s"file://$aclsPath",
      "MESOS_CREDENTIALS" -> s"file://$credentialsPath",
      "MESOS_SYSTEMD_ENABLE_SUPPORT" -> "false",
      "MESOS_SWITCH_USER" -> "false") ++
      config.isolation.map("MESOS_ISOLATION" -> _).to[Seq] ++
      config.imageProviders.map("MESOS_IMAGE_PROVIDERS" -> _).to[Seq]
  }

  case class Mesos(master: Boolean, extraArgs: Seq[String]) extends AutoCloseable {
    val ip = IP.routableIPv4
    val port = PortAllocator.ephemeralPort()
    private val workDir = Files.createTempDirectory(s"mesos-master$port").toFile
    private val processBuilder = Process(
      command = Seq(
      "mesos",
      if (master) "master" else "slave",
      s"--ip=$ip",
      s"--hostname=$ip",
      s"--port=$port",
      if (master) s"--zk=$masterUrl" else s"--master=$masterUrl",
      s"--work_dir=${workDir.getAbsolutePath}") ++ extraArgs,
      cwd = None, extraEnv = mesosEnv(workDir): _*)
    private var process = Option.empty[Process]

    if (autoStart) {
      start()
    }

    def start(): Unit = if (process.isEmpty) {
      process = Some(create())
    }

    def stop(): Unit = {
      process.foreach(_.destroy())
      process = Option.empty[Process]
    }

    private def create(): Process = {
      val name = if (master) "Master" else "Agent"
      processBuilder.run(ProcessOutputToLogStream(s"$suiteName-Mesos$name-$port"))
    }

    override def close(): Unit = {
      def copySandboxFiles() = {
        val projectDir = sys.props.getOrElse("user.dir", ".")
        println(s">>> Copying sandbox files to from $workDir to $projectDir")
        FileUtils.copyDirectory(workDir, Paths.get(projectDir, "sandboxes", suiteName).toFile)
      }
      // Copy all sandbox files (useful for debugging) into current directory
      // for Jenkins to archive it:
      Try(copySandboxFiles()) match {
        case Success(_) =>
        case Failure(ex) => println(s">>> Failed to copy sandbox files: ${ex}")
      }

      stop()
      Try(FileUtils.deleteDirectory(workDir))
    }
  }

  def state = new MesosFacade(Await.result(waitForLeader(), waitForLeaderTimeout)).state

  def clean(): Unit = {
    val client = new MesosFacade(Await.result(waitForLeader(), waitForLeaderTimeout))
    MesosTest.clean(client)
  }

  override def close(): Unit = {
    Try(clean())
    agents.foreach(_.close())
    masters.foreach(_.close())
  }

}

trait MesosTest {
  def mesos: MesosFacade
  val mesosMasterUrl: String
  def cleanMesos(): Unit
}

object MesosTest {
  def clean(client: MesosFacade, cleanTimeout: Duration = 5.minutes)(implicit ec: ExecutionContext, s: Scheduler): Unit = {
    def teardown: Future[Done] =
      Retry("teardown marathon", maxDuration = cleanTimeout, minDelay = 0.25.second, maxDelay = 2.second) {
        Future.fold(client.frameworkIds().value.map(client.teardown(_).map { response =>
          val status = response.status.intValue
          if (status == 200) Done
          else throw new IllegalStateException(s"server returned status $status")
        }))(Done) { (_, _) => Done }.map { _ =>
          val agents = client.state.value.agents
          agents.find(a => !a.usedResources.isEmpty || a.reservedResourcesByRole.nonEmpty).fold(Done) { agent =>
            throw new IllegalStateException(
              s"agent allocated on agent ${agent.id}: " +
                s"used = ${agent.usedResources}, reserved = ${agent.reservedResourcesByRole}")
          }
        }
      }
    Await.result(teardown, Duration.Inf)
  }
}

trait SimulatedMesosTest extends MesosTest {
  def mesos: MesosFacade = {
    require(false, "No access to mesos")
    ???
  }
  def cleanMesos(): Unit = {}
  val mesosMasterUrl = ""
}

trait MesosClusterTest extends Suite with ZookeeperServerTest with MesosTest with ScalaFutures {
  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler

  private val localMesosUrl = sys.env.get("USE_LOCAL_MESOS")
  lazy val mesosMasterUrl = s"zk://${zkServer.connectUri}/mesos"
  lazy val mesosNumMasters = 1
  lazy val mesosNumSlaves = 1
  lazy val mesosQuorumSize = 1
  lazy val mesosConfig = MesosConfig()
  lazy val mesosLeaderTimeout: FiniteDuration = patienceConfig.timeout.toMillis.milliseconds
  lazy val mesosCluster = MesosCluster(suiteName, mesosNumMasters, mesosNumSlaves, mesosMasterUrl, mesosQuorumSize,
    autoStart = false, config = mesosConfig, mesosLeaderTimeout)
  lazy val mesos = new MesosFacade(localMesosUrl.getOrElse(mesosCluster.waitForLeader().futureValue))

  override def cleanMesos(): Unit = mesosCluster.clean()

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    localMesosUrl.fold(mesosCluster.start().futureValue)(identity)
  }

  abstract override def afterAll(): Unit = {
    localMesosUrl.fold(mesosCluster.close())(_ => ())
    super.afterAll()
  }
}

object IP {
  import sys.process._

  lazy val routableIPv4: String =
    sys.env.getOrElse("MESOSTEST_IP_ADDRESS", inferRoutableIP)

  private def detectIpScript = {
    val resource = getClass.getClassLoader.getResource("detect-routable-ip.sh")
    Option(resource).flatMap { f => Option(f.getFile) }.getOrElse {
      throw new RuntimeException(
        s"Couldn't find file for detect-routable-ip.sh resource; is ${resource}. Are you running from a JAR?")
    }
  }

  private def inferRoutableIP: String = {
    Seq("bash", detectIpScript).!!.trim
  }
}
