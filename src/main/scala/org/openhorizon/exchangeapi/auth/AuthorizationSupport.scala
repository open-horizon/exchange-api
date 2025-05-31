package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter

import javax.security.auth.Subject
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util._

import org.openhorizon.exchangeapi.auth.Access._


/* Not using the java authorization framework anymore, because it doesn't add any value for us and adds complexity
// Authorization and its subclasses are used by the authorizeTo() methods in Identity subclasses
sealed trait Authorization {
  def as(subject: Subject): Unit
  def specificAccessRequired: Access
}

final case class RequiresAccess(specificAccess: Access) extends Authorization {
  override def as(subject: Subject): Unit = {
    Subject.doAsPrivileged(subject, PermissionCheck(specificAccess.toString), null)
  }

  override def specificAccessRequired = specificAccess
}
*/

/*
AuthorizationSupport is used by AuthenticationSupport, auth/Module, and auth/IbmCloudModule.
It contains several authentication utilities:
  - AuthenticatedIdentity
  - all the Identity subclasses (used for local authentication)
  - all the Target subclasses (used for authorization)
 */
trait AuthorizationSupport {
  implicit def logger: LoggingAdapter

  // Embodies both the exchange-specific Identity, and the JAAS/javax.security.auth Subject
  // Note: this is defined here, instead of AuthenticationSupport, because its authorizeTo() method uses this class extensively
  case class AuthenticatedIdentity(identity: Identity, subject: Subject) {

    // Determines if this authenticated identity has the specified access to the specified target
    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      
      //logger.debug(s"${target.toString},  ${access.toString},  ${identity.identity2.toString}")
      
      try {
        identity.role match {
          case  AuthRoles.User |
                AuthRoles.AdminUser |
                AuthRoles.HubAdmin |
                AuthRoles.SuperUser =>
            if (target.getId == "iamapikey" ||
                target.getId == "iamtoken") {
              // This is a cloud IAM user. Get the actual username before continuing.
              //logger.debug("HERE")
              identity.authorizeTo(TUser(identity.identity2.resource), access)
            }
            else {
              //logger.debug("THERE")
              identity.authorizeTo(target, access)
            }
          case _ =>
            // This is an exchange node or agbot
            //logger.debug("SOMEWHERE")
            identity.authorizeTo(target, access)
        }
      }
      catch {
        case _: Exception =>
          Failure(AccessDeniedException(identity.accessDeniedMsg(access, target)))
      }
    }
  }
}
