package org.openhorizon.exchangeapi.utility
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import org.mindrot.jbcrypt.BCrypt
object ApiKeyUtils {
  private val secureRandom = new SecureRandom()

  def generateApiKeyValue(): String = {
    var bytes:Array[Byte] = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) // URL-safe key
  }

  def sha256Hash(str: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(str.getBytes("UTF-8"))
    md.digest.map("%02x".format(_)).mkString
  }

  def bcryptHash(str: String): String = {
    BCrypt.hashpw(str, org.mindrot.jbcrypt.BCrypt.gensalt(10))
}

  def generateApiKeyId(): String = UUID.randomUUID().toString
}
