package scala.meta.internal.builds

import sys.process._
import scala.concurrent.Future
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.BspServers
import java.nio.file.Files
import scala.meta.internal.metals.SbtOpts
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import com.zaxxer.nuprocess.NuProcessBuilder
import scala.concurrent.ExecutionContext
import com.zaxxer.nuprocess.NuProcess
import scala.meta.internal.metals.BspSession
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import java.nio.ByteBuffer
import scala.meta.internal.metals.Directories
import java.nio.charset.StandardCharsets
import scala.concurrent.Promise
import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Tables

class SbtInstall(
  workspace: AbsolutePath,
  buildTools: BuildTools,
  bspServers: BspServers,
  shellRunner: ShellRunner,
  tables: Tables,
  userConfig: () => UserConfiguration,
  runDisconnect: () => Future[Unit],
  runConnect: BspSession => Future[Unit],
)(implicit ec: ExecutionContext) {
  var sbtProcess: Option[NuProcess] = None

  protected lazy val tempDir = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  lazy val embeddedSbtLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  def disconnect(): Unit = {
    sbtProcess.map{p => 
      scribe.info(s"Killing running sbt process.")
      p.destroy(false)
      p.waitFor(10, TimeUnit.SECONDS)
    }
  }

  def connect(): Future[Unit] = {
    scribe.info("Trying to connect directly to SBT BSP")
    if (buildTools.isSbt) {
      scribe.info("Sbt build verified")
      connectSbt()
    } else {
      scribe.info("No sbt build")
      Future.successful(())
    }
  }

  private def connectSbt(): Future[Unit] = {
    scribe.info("Generating configuration file sbt.json")
    val output = "sbt about"!!

    val sbtVersion = output
      .split(System.lineSeparator())
      .filter(_.contains("This is sbt"))
      .map(s => s.substring(s.indexOf("This is sbt")))
      .mkString

    scribe.info(s"Sbt version: ${sbtVersion}")

    val sbtBspConfig = workspace.resolve(".bsp").resolve("sbt.json")
    if (sbtBspConfig.isFile) {
      scribe.info(s"We found sbt.json, ready to connect")
      connectSbtWithConfig()
    } else {
      scribe.info(s"Sbt probably too old, sbt.json not found")
      Future.successful(())
    }
  }

  private def installSbtPlugin(): Unit = {
    val metalsPluginFile = workspace.resolve("project").resolve("MetalsSbtBsp.scala")
    if (!metalsPluginFile.isFile) {
      scribe.info(s"Install plugin to ${metalsPluginFile}")
      BuildTool.copyFromResource("MetalsSbtBsp.scala", metalsPluginFile.toNIO)
    }
  }


  private def runSbtShell(): (NuProcess, SbtProcessHandler) = {
    val sbtArgs = List() // what to run actually???

    val javaArgs = List[String](
      JavaBinary(userConfig().javaHome),
      "-Djline.terminal=jline.UnsupportedTerminal",
      "-Dsbt.log.noformat=true",
      "-Dfile.encoding=UTF-8"
    )
    val jarArgs = List(
      "-jar",
      embeddedSbtLauncher.toString()
    )

    val runCommand = List(
      javaArgs,
      SbtOpts.fromWorkspace(workspace),
      JvmOpts.fromWorkspace(workspace),
      jarArgs,
      sbtArgs
    ).flatten

    run(
        runCommand,
        workspace
    )
  }




  private def connectSbtWithConfig(): Future[Unit] = {
    scribe.info(s"Copying metals plugin")
    installSbtPlugin()

    runDisconnect().map { _ =>
      val (sbt, handler) = runSbtShell()
      sbtProcess = Some(sbt)

      scribe.info(s"SBT process started: ${sbt.isRunning}")
      handler.initialized.future.flatMap { _ =>
        scribe.info(s"sbt up and running")
        initialize()
      }
    }

  }

  def initialize(): Future[Unit] = {
    val detailsMaybe = bspServers.findAvailableServers().find(_.getName() == "sbt")
    val sessionMaybe = detailsMaybe.map(c => bspServers.newServer(workspace, c).map(bsc => BspSession(bsc, Nil)))

    detailsMaybe.foreach(details => tables.buildServers.chooseServer(details.getName))

    sessionMaybe match {
      case None => Future.successful(())
      case Some(sessionF) => sessionF.flatMap { session =>
        val c = runConnect(session)
        session.mainConnection.onReconnection { newMainConn =>
          val updSession = session.copy(main = newMainConn)
          runConnect(updSession).map(_ => ())
        }
        c.onComplete(r => scribe.info(s"Completed connection with ${r}"))
        c.map(_ => ())
      }
    }
  }

  private def run(
      args: List[String],
      directory: AbsolutePath,
      additionalEnv: Map[String, String] = Map.empty
  )(implicit ec: ExecutionContext): (NuProcess, SbtProcessHandler) = {
    val elapsed = new Timer(Time.system)
    scribe.info("Starting background sbt process")
    val handler = new SbtProcessHandler(workspace)
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(directory.toNIO)
    userConfig().javaHome.foreach(pb.environment().put("JAVA_HOME", _))
    additionalEnv.foreach {
      case (key, value) =>
        pb.environment().put(key, value)
    }
    val runningProcess = pb.start()
    handler.completeProcess.future.foreach { result =>
      scribe.info(s"sbt background process stopped. Ran for $elapsed")
    }
    (runningProcess, handler)
  }

}

class SbtProcessHandler(workspace: AbsolutePath) extends NuAbstractProcessHandler {
  val sbtLogFile = workspace.resolve(Directories.sbtlog)

  val initialized: Promise[Boolean] = Promise[Boolean]()
  val completeProcess: Promise[Int] = Promise[Int]()

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
      val msg = StandardCharsets.UTF_8.decode(buffer).toString()
      sbtLogFile.appendText(msg)
      if (!initialized.isCompleted && msg.contains("sbt server started at"))
        initialized.trySuccess(true)
      if (!initialized.isCompleted && msg.contains("another instance of sbt running on this build"))
        initialized.trySuccess(false)
      super.onStdout(buffer, closed)
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
      val msg = StandardCharsets.UTF_8.decode(buffer).toString()
      sbtLogFile.appendText(msg)
      super.onStderr(buffer, closed)
  }

  override def onExit(statusCode: Int): Unit = completeProcess.trySuccess(statusCode)
}