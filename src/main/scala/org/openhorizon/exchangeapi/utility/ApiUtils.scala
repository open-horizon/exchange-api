/** Helper classes for the exchange api rest methods, including some of the common case classes used by the api. */
package org.openhorizon.exchangeapi.utility

import org.json4s.JsonAST.JValue
import org.json4s._

import java.lang.management.{ManagementFactory, RuntimeMXBean}
import java.util
import java.util.{Base64, Properties}


object ApiUtils {
  def encode(unencodedCredStr: String): String = Base64.getEncoder.encodeToString(unencodedCredStr.getBytes("utf-8"))

  // Convert an AnyRef to JValue
  def asJValue(src: AnyRef): JValue = {
    import org.json4s.jackson.Serialization
    import org.json4s.{Extraction, NoTypeHints}
    implicit val formats: AnyRef with Formats = Serialization.formats(NoTypeHints)

    Extraction.decompose(src)
  }

  // Get the JVM arguments that were passed to it
  def getJvmArgs: util.List[String] = {
    val runtimeMXBean: RuntimeMXBean = ManagementFactory.getRuntimeMXBean
    val jvmArgs: util.List[String] = runtimeMXBean.getInputArguments
    jvmArgs
  }

  /* This apparently ony works when run from a war file, not straight from sbt. Get our version from build.sbt
  def getAppVersion = {
    val p = getClass.getPackage
    p.getImplementationVersion
  }
  */
}
