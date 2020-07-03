package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class BootstrapModifier extends StringModifier {
  override val name: String = "bootstrap"
  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    info.split(" ") match {
      case Array(binary, client) =>
        s"""
           |Next, build a `$binary` binary for the latest Metals release using the
           |[Coursier](https://github.com/coursier/coursier) command-line interface.
           |
           |${Docs.releasesResolverTable}
           |
           |```sh
           |# Make sure to use coursier v1.1.0-M9 or newer.
           |curl -L -o coursier https://git.io/coursier-cli
           |chmod +x coursier
           |./coursier install $binary --install-dir /usr/local/bin/
           |
           |# Updating a binary to newest version
           |./coursier update $binary
           |```
           |
           |Make sure the generated `$binary` binary is available on your `$$PATH`.
           |
           |Default memory settings are '-Xms100m' '-Xss4m' but you can override them
           |using '$$JAVA_OPTS'
           |
           |You can check version of your binary by executing `$binary -version`.
           |
           |Configure the system properties `-Dhttps.proxyHost=… -Dhttps.proxyPort=…`
           |if you are behind an HTTP proxy.
           |""".stripMargin
      case _ =>
        reporter.error(s"Invalid info '$info'. Expected '<binary> <client>'")
        ""
    }
  }
}
