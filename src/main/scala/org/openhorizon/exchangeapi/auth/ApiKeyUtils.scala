package org.openhorizon.exchangeapi.auth
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, UUID}
import java.net.URLEncoder

case object ApiKeyUtils {
  def generateApiKeyHashedValue(): String = {
    var bytes:Array[Byte] = new Array[Byte](32)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(bytes)
    val base64 = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val sha256 = MessageDigest.getInstance("SHA-256")
      .digest(base64.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString
    URLEncoder.encode(sha256, "UTF-8")
  }

  def generateApiKeyId(): UUID = UUID.randomUUID()
}
