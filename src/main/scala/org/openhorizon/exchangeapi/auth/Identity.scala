package org.openhorizon.exchangeapi.auth

import akka.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.ExchMsg

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

/** This class and its subclasses represent the identity that is used as credentials to run rest api methods */
abstract class Identity {
  def creds: Creds
  def role: String = ""   // set in the subclasses to a role defined in Role
  def toIUser: IUser = IUser(creds)
  def toINode: INode = INode(creds)
  def toIAgbot: IAgbot = IAgbot(creds)
  def toIAnonymous: IAnonymous = IAnonymous(Creds("",""))
  def isSuperUser = false       // IUser overrides this
  def isAdmin = false       // IUser overrides this
  def isHubAdmin = false       // IUser overrides this
  def isAnonymous = false // = creds.isAnonymous
  def identityString: String = creds.id     // for error msgs
  def accessDeniedMsg(access: Access, target: Target): String = ExchMsg.translate("access.denied.no.auth", identityString, access, target.toAccessMsg)
  //var hasFrontEndAuthority = false   // true if this identity was already vetted by the front end
  def isMultiTenantAgbot: Boolean = false

/** Returns true if the token is correct for this user and not expired */
def isTokenValid(token: String, username: String): Boolean = {
  // Get their current hashed pw to use as the secret
  AuthCache.getUser(username) match {
    case Some(userHashedTok) => Token.isValid(token, userHashedTok)
    case None => throw new InvalidCredentialsException(ExchMsg.translate("invalid.credentials"))
  }
}

  // Called by auth/Module.login() to authenticate a local user/node/agbot
  // Note: this is defined here, instead of AuthenticationSupport, because this class is already set up to use AuthCache
  def authenticate(hint: String = ""): Identity = {
    //if (hasFrontEndAuthority) return this       // it is already a specific subclass
    //if (creds.isAnonymous) return toIAnonymous
    if (hint == "token") {
      if (isTokenValid(creds.token, creds.id)) return toIUser
      //else throw new InvalidCredentialsException("invalid token")  <- hint==token means it *could* be a token, not that it *must* be
    }
    AuthCache.ids.getValidType(creds) match {
      case Success(cacheIdType) =>
        cacheIdType match {
          case CacheIdType.User => toIUser
          case CacheIdType.Node => toINode
          case CacheIdType.Agbot => toIAgbot
          case CacheIdType.None => throw new InvalidCredentialsException() // will be caught by AuthenticationSupport.authenticate()
        }
      case Failure(t) => throw t  // this is usually 1 of our exceptions - will be caught by AuthenticationSupport.authenticate()
    }
  }

  def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity]

  def getOrg: String = {
    val reg: Regex = """^(\S+?)/.*""".r
    creds.id match {
      case reg(org) => org
      case _ => ""
    }
  }

  def getIdentity: String = {
    val reg: Regex = """^\S+?/(\S+)$""".r
    creds.id match {
      case reg(id) => id
      case _ => ""
    }
  }

  def isMyOrg(target: Target): Boolean = {
    target.getOrg == getOrg
  }
}
