package scala.meta.internal.metals

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * ClassLoader that is used to reflectively invoke presentation compiler APIs.
 *
 * The presentation compiler APIs are compiled against exact Scala versions of the compiler
 * while Metals only runs in a single Scala version. In order to communicate between Metals and the
 * reflectively loaded compiler, this classloader shares a subset of Java classes that appear in
 * method signatures of the `PresentationCompiler` class.
 */
class PresentationCompilerClassLoader(parent: ClassLoader)
    extends ClassLoader(PresentationCompilerClassLoader.bootClassLoader) {
  override def findClass(name: String): Class[_] = {
    val isShared =
      name.startsWith("org.eclipse.lsp4j") ||
        name.startsWith("com.google.gson") ||
        name.startsWith("scala.meta.pc")
    if (isShared) {
      parent.loadClass(name)
    } else {
      super.findClass(name)
    }
  }
}

object PresentationCompilerClassLoader {
  // based on https://github.com/scala/scala/pull/6098
  private val bootClassLoader: ClassLoader = {
    if (!util.Properties.isJavaAtLeast("9")) {
      null
    } else {
      try MethodHandles
        .lookup()
        .findStatic(
          classOf[ClassLoader],
          "getPlatformClassLoader",
          MethodType.methodType(classOf[ClassLoader])
        )
        .invoke()
      catch {
        case _: Throwable =>
          null
      }
    }
  }
}
