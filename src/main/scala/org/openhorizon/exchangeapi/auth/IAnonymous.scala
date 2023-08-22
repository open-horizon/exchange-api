package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.ExchMsg

import scala.util.{Failure, Success, Try}

// Note: i don't think this is used now
case class IAnonymous(creds: Creds) extends Identity {
  override lazy val role: String = AuthRoles.Anonymous

  def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity] = {
    // Transform any generic access into specific access
    val requiredAccess: Access =
      // Note: IAnonymous.isMyOrg() is always false (except for admin operations), so if we had another route that should work for anonymous, so something like:
      //if (access == Access.RESET_USER_PW) Access.RESET_USER_PW else
      if (isMyOrg(target) || target.isPublic) {
        target match {
          case TUser(id) => access match { // an anonymous accessing a user
            case Access.READ => Access.READ_ALL_USERS
            case Access.WRITE => Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
          case TNode(_) => access match { // an anonymous accessing a node
            case Access.READ => Access.READ_ALL_NODES
            case Access.WRITE => Access.WRITE_ALL_NODES
            case Access.CREATE => Access.CREATE_NODE
            case _ => access
          }
          case TAgbot(_) => access match { // an anonymous accessing a agbot
            case Access.READ => Access.READ_ALL_AGBOTS
            case Access.WRITE => Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
          case TService(_) => access match { // an anonymous accessing a service
            case Access.READ => Access.READ_ALL_SERVICES
            case Access.WRITE => Access.WRITE_ALL_SERVICES
            case Access.CREATE => Access.CREATE_SERVICES
            case _ => access
          }
          case TPattern(_) => access match { // an anonymous accessing a pattern
            case Access.READ => Access.READ_ALL_PATTERNS
            case Access.WRITE => Access.WRITE_ALL_PATTERNS
            case Access.CREATE => Access.CREATE_PATTERNS
            case _ => access
          }
          case TBusiness(_) => access match { // an anonymous accessing a business policy
            case Access.READ => Access.READ_ALL_BUSINESS
            case Access.WRITE => Access.WRITE_ALL_BUSINESS
            case Access.CREATE => Access.CREATE_BUSINESS
            case _ => access
          }
          case TManagementPolicy(_) => access match { // an anonymous accessing a management policy
            case Access.READ => Access.READ_ALL_MANAGEMENT_POLICY
            case Access.WRITE => Access.WRITE_ALL_MANAGEMENT_POLICY
            case Access.CREATE => Access.CREATE_MANAGEMENT_POLICY
            case _ => access
          }
          case TOrg(_) => access match { // an anonymous accessing his org resource
            case Access.READ => Access.READ_MY_ORG
            case Access.WRITE => Access.WRITE_MY_ORG
            case Access.CREATE => Access.CREATE_ORGS
            case _ => access
          }
          case TAction(_) => access // an anonymous running an action
        }
      } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
        Access.NOT_FOUND
      } else {  // not my org and not public, but is there or we might create it
        access match {
          case Access.READ => Access.READ_OTHER_ORGS
          case Access.WRITE => Access.WRITE_OTHER_ORGS
          case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
          case _ => access
        }
      }
    if (requiredAccess == Access.NOT_FOUND) Failure(new ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
    else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
    else Failure(new AccessDeniedException(accessDeniedMsg(requiredAccess, target)))
  }
}
