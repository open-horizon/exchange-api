package org.openhorizon.exchangeapi.auth

import org.mindrot.jbcrypt.BCrypt

import scala.util.matching.Regex

/** Hash a password or token, and compare a pw/token to its hashed value */
object Password {
  // Using jbcrypt, see https://github.com/jeremyh/jBCrypt and http://javadox.com/org.mindrot/jbcrypt/0.3m/org/mindrot/jbcrypt/BCrypt.html
  val defaultLogRounds = 10 // hashes the pw 2**logRounds times
  val minimumLogRounds = 4 // lowest brcypt will accept

  /**
   * Returns the hashed value of the given password or token. Lowest logRounds allowed is 4.
   * Note: since BCrypt.hashpw() uses a different salt each time, 2 hashes of the same pw will be different. So it is not valid to hash the
   *     clear pw specified by the user and compare it to the already-hashed pw in the db. You must use BCrypt.checkpw() instead.
   */
  def hash(password: String): String =
    BCrypt.hashpw(password, BCrypt.gensalt(defaultLogRounds))

  def fastHash(password: String): String =
    BCrypt.hashpw(password, BCrypt.gensalt(minimumLogRounds))

  /** Returns true if plainPw matches hashedPw */
  def check(plainPw: String, hashedPw: String): Boolean = {
    if (hashedPw == "") return false // this covers the case when the root user is disabled
    BCrypt.checkpw(plainPw, hashedPw)
  }

  /** Returns true if this pw/token is already hashed */
  def isHashed(password: String): Boolean = {
    //password.startsWith("""$2a$10$""")
    // bcrypt puts $2a$10$ at the beginning of encrypted values, where the 10 is the logRounds used (it will always be a 2 digit number)
    val regex: Regex = raw"""^\$$2a\$$\d\d\$$""".r
    regex.findFirstIn(password).isDefined
  }

  /** If already hash, return it, otherwise hash it */
  def hashIfNot(password: String): String = if (password.isEmpty || isHashed(password)) password else hash(password)
}
