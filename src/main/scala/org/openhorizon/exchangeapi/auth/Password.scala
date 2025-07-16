package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.utility.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

/** Hash a password or token, and compare a pw/token to its hashed value */
case object Password {
  // https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
  // https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#argon2id
  /**
    * The following are all at the same work factor trading memory and cpu compute.
    *
    * m=47104 (46 MiB), t=1, p=1 (Do not use with Argon2i)
    * m=19456 (19 MiB), t=2, p=1 (Do not use with Argon2i)
    * m=12288 (12 MiB), t=3, p=1
    * m=9216  ( 9 MiB), t=4, p=1
    * m=7168  ( 7 MiB), t=5, p=1
    */
  private val hash_length: Int = Configuration.getConfig.getInt("api.cryptography.argon2id.hash_length")
  private val iterations:  Int = Configuration.getConfig.getInt("api.cryptography.argon2id.iterations")
  private val memory:      Int = Configuration.getConfig.getInt("api.cryptography.argon2id.memory")
  private val parallelism: Int = Configuration.getConfig.getInt("api.cryptography.argon2id.parallelism")
  private val salt_length: Int = Configuration.getConfig.getInt("api.cryptography.argon2id.salt_length")
  
  private val argon2idEncoder = new Argon2PasswordEncoder(salt_length, hash_length, parallelism, memory, iterations);
  
  // DO NOT use this encoder for credential storage. This implementation does not contain any form of workfactor (Security).
  private val argon2idEncoderNoWorkfactor = new Argon2PasswordEncoder(2, 4, 1, 0, 1);
  
  /** Returns true if plainPw matches hashedPw */
  def check(plainPw: String, hashedPw: String): Boolean =
    if (hashedPw == "")
      false // this covers the case when the user is disabled
    else
      argon2idEncoder.matches(plainPw, hashedPw)
  
  /**
    * Returns the hashed value of the given password or token.
    *
    * @param password The plain text credential to hash.
    */
  def hash(password: String): String =
    argon2idEncoder.encode(password)
  
  /**
   * Returns the hashed value of the given password or token
   * WITHOUT using a workfactor. DO NOT use this function
   * for credential storage.
   *
   * @param password The plain text credential to hash.
   */
  def hashNoWorkfactor(password: String): String =
    argon2idEncoderNoWorkfactor.encode(password)
}
