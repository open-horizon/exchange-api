package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.ExchMsg

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case class INode(creds: Creds,
                 identity: Identity2) extends Identity {
  def this(identity: Identity2) =
    this(creds = Creds(id = identity.resource),
         identity = identity)
  
  override lazy val role: String = identity.role
  
  override def identity2: Identity2 = identity.copy()

  def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity] = {
    // Transform any generic access into specific access
    val requiredAccess: Access =
      if (isMyOrg(target) || target.isPublic || isMsgToMultiTenantAgbot(target,access)) {
        target match {
          case TUser(id, _) => access match { // a node accessing a user
            case Access.READ => Access.READ_ALL_USERS
            case Access.WRITE => Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
          case TNode(id, _) => access match { // a node accessing a node
            case Access.READ => if (id == creds.id) Access.READ_MYSELF else if (target.mine) Access.READ_MY_NODES else Access.READ_ALL_NODES
            case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else if (target.mine) Access.WRITE_MY_NODES else Access.WRITE_ALL_NODES
            case Access.CREATE => Access.CREATE_NODE
            case _ => access
          }
          case TAgbot(_, _) => access match { // a node accessing a agbot
            case Access.READ => Access.READ_ALL_AGBOTS
            case Access.WRITE => Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
          case TService(_, _, _) => access match { // a node accessing a service
            case Access.READ => Access.READ_ALL_SERVICES
            case Access.WRITE => Access.WRITE_ALL_SERVICES
            case Access.CREATE => Access.CREATE_SERVICES
            case _ => access
          }
          case TPattern(_, _, _) => access match { // a node accessing a pattern
            case Access.READ => Access.READ_ALL_PATTERNS
            case Access.WRITE => Access.WRITE_ALL_PATTERNS
            case Access.CREATE => Access.CREATE_PATTERNS
            case _ => access
          }
          case TBusiness(_, _) => access match { // a node accessing a business policy
            case Access.READ => Access.READ_ALL_BUSINESS
            case Access.WRITE => Access.WRITE_ALL_BUSINESS
            case Access.CREATE => Access.CREATE_BUSINESS
            case _ => access
          }
          case TManagementPolicy(_, _) => access match { // a node accessing a business policy
            case Access.READ => Access.READ_ALL_MANAGEMENT_POLICY
            case Access.WRITE => Access.WRITE_ALL_MANAGEMENT_POLICY
            case Access.CREATE => Access.CREATE_MANAGEMENT_POLICY
            case _ => access
          }
          case TOrg(_) => access match { // a node accessing his org resource
            case Access.READ => Access.READ_MY_ORG
            case Access.WRITE => Access.WRITE_MY_ORG
            case Access.CREATE => Access.CREATE_ORGS
            case _ => access
          }
          case TAction(_) => access // a node running an action
        }
      } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
        Access.NOT_FOUND
      } else {  // not my org and not public, not a msg send to agbot, but is there or we might create it
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

  def isMsgToMultiTenantAgbot(target: Target, access: Access): Boolean = {
    target.getOrg == "IBM" && (access == Access.SEND_MSG_TO_AGBOT || access == Access.READ)    //someday: implement instance-level ACLs instead of hardcoding this
  }
}
