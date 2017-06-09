package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.ContainerInfo
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.collection.breakOut

@State(Scope.Benchmark)
object FlatDependencyBenchmark {

  val version = AppDefinition.VersionInfo.forNewConfig(Timestamp(1))

  def makeApp(path: PathId) =
    AppDefinition(
      id = path,
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      container = Some(
        Docker(Nil, "alpine",
          network = Some(ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(List(Docker.PortMapping(2015, Some(0), 10000, "tcp", Some("thing"))))))
    )
  val ids = 0 to 900

  val appPaths: Vector[PathId] = ids.map { appId =>
    s"/app-${appId}".toPath
  }(breakOut)

  val appDefs: Map[PathId, AppDefinition] = appPaths.map { path =>
    path -> makeApp(path)
  }(breakOut)

  val rootGroup = Group("/".toPath, apps = appDefs)
  def upgraded = {
    val pathId = "/app-901".toPath
    Group(
      "/".toPath,
      apps = rootGroup.apps + (pathId -> makeApp(pathId)))
  }
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class FlatDependencyBenchmark {
  import FlatDependencyBenchmark._

  @Benchmark
  def deploymentPlanDependencySpeed(hole: Blackhole): Unit = {
    val deployment = DeploymentPlan(rootGroup, upgraded)
    hole.consume(deployment)
  }
}
