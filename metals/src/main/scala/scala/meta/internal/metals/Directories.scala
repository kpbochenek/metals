package scala.meta.internal.metals
import scala.meta.io.RelativePath

object Directories {
  def database: RelativePath =
    RelativePath(".metals").resolve("metals.h2.db")
  def readonly: RelativePath =
    RelativePath(".metals").resolve("readonly")
  def log: RelativePath =
    RelativePath(".metals").resolve("metals.log")
  def semanticdb: RelativePath =
    RelativePath("META-INF").resolve("semanticdb")
  def pc: RelativePath =
    RelativePath(".metals").resolve("pc.log")
  def workspaceSymbol: RelativePath =
    RelativePath(".metals").resolve("workspace-symbol.md")
  def stacktrace: RelativePath =
    RelativePath(".metals").resolve(stacktraceFilename)

  val stacktraceFilename = "stacktrace.scala"
}
