package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.utility.ApiTime

/** Create and validate web tokens that expire */
/*object Token {
  // From: https://github.com/pauldijou/jwt-scala
  val defaultExpiration = 600 // seconds
  val algorithm: JwtAlgorithm.HS256.type = JwtAlgorithm.HS256

  /** Returns a temporary pw reset token. */
  def create(secret: String, expiration: Int = defaultExpiration): String = {
    //implicit val clock: Clock = Clock.systemUTC()
    //Jwt.encode(JwtClaim({"""{"user":1}"""}).issuedNow.expiresIn(defaultExpiration), secret, algorithm)
    Jwt.encode(JwtClaim({ """{"user":1}""" }).expiresAt(ApiTime.nowSeconds + expiration), secret, algorithm)
  }

  /** Returns true if the token is correct for this secret and not expired */
  def isValid(token: String, secret: String): Boolean = { Jwt.isValid(token, secret, Seq(algorithm)) }
}*/
