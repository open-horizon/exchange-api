package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.ExchMsg

import java.util.UUID
import scala.util.{Failure, Success, Try}

case class IUser(creds: Creds,
                 identity: Identity2) extends Identity {
  def this(identity: Identity2) =
    this(creds = Creds(id = identity.resource),
         identity = identity)
  
  def resource: String = identity.resource
  
  override def identity2: Identity2 = identity.copy()
  
  override def isSuperUser: Boolean = identity.isSuperUser

  override lazy val role: String = identity.role

  override def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity] = {
    val requiredAccess: Access =
      // Transform any generic access into specific access
      if (isMyOrg(target) || target.isPublic) {
        target match {
          case TUser(id, _) => access match { // a user accessing a user
            case Access.READ => logger.debug(s"authorizeTo(): target id=$id, identity creds.id=${creds.id}")
              if (id == creds.id) Access.READ_MYSELF
              // since we are in the section in which identity and target are in the same org, if identity is a hub admin we are viewing the users in the root org. Note: the root user is also an hub admin and org admin.
              else if (isHubAdmin || target.mine) Access.READ_MY_USERS // the routes's getAnyProblem() methods will catch the rest of the hubAdmin cases
              else Access.READ_ALL_USERS
            case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF
              else if (target.isSuperUser) Access.WRITE_SUPERUSER
              else if (isHubAdmin  || target.mine) Access.WRITE_MY_USERS // the routes's getAnyProblem() methods will catch the rest of the hubAdmin cases
              else Access.WRITE_ALL_USERS
            case Access.CREATE => if (target.isSuperUser) Access.CREATE_SUPERUSER
              else Access.CREATE_USER // this also applies to a hub admin creating another hub admin
            case _ => access
          }
          case TNode(_, _) => access match { // a user accessing a node
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_NODES else Access.READ_ALL_NODES
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_NODES else Access.WRITE_ALL_NODES
            case Access.CREATE => Access.CREATE_NODE // not used, because WRITE is used for create also
            case _ => access
          }
          case TAgbot(_, _) => access match { // a user accessing a agbot
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
          case TService(_, _, _) => access match { // a user accessing a service
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_SERVICES else Access.READ_ALL_SERVICES
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_SERVICES else Access.WRITE_ALL_SERVICES
            case Access.CREATE => Access.CREATE_SERVICES
            case _ => access
          }
          case TPattern(_, _, _) => access match { // a user accessing a pattern
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_PATTERNS else Access.READ_ALL_PATTERNS
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_PATTERNS else Access.WRITE_ALL_PATTERNS
            case Access.CREATE => Access.CREATE_PATTERNS
            case _ => access
          }
          case TBusiness(_, _) => access match { // a user accessing a business policy
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_BUSINESS else Access.READ_ALL_BUSINESS
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_BUSINESS else Access.WRITE_ALL_BUSINESS
            case Access.CREATE => Access.CREATE_BUSINESS
            case _ => access
          }
          case TManagementPolicy(_, _) => access match { // a user accessing a business policy
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_MANAGEMENT_POLICY else Access.READ_ALL_MANAGEMENT_POLICY
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_MANAGEMENT_POLICY else Access.WRITE_ALL_MANAGEMENT_POLICY
            case Access.CREATE => Access.CREATE_MANAGEMENT_POLICY
            case _ => access
          }
          case TOrg(_) => access match {    // a user accessing the org resource he is part of
            case Access.READ => Access.READ_MY_ORG
            case Access.READ_IBM_ORGS => Access.READ_IBM_ORGS
            case Access.WRITE if (Role.isHubAdmin(creds.id)) => Access.WRITE_ALL_ORGS // this is modifying the root org
            case Access.WRITE => Access.WRITE_MY_ORG // an org admin modifying their own org
            case Access.DELETE_ORG if (Role.isHubAdmin(creds.id)) => Access.NEVER_ALLOWED // never allowed to delete
            case Access.CREATE => Access.CREATE_ORGS
            case _ => access // this includes the case of an org admin trying to DELETE_ORG
          }
          case TAction(_) => access // a user running an action
        }
      } else if (isHubAdmin) { // cross-org access is "normal" for a hub admin, because the hub admin is defined in the root org
        // since we are in the cross-org section, the target will never be itself or root
        target match {
          case TUser(id, _) => access match { // a hub admin accessing a user
              case Access.READ => logger.debug(s"authorizeTo(): target id=$id, identity creds.id=${creds.id}")
                Access.READ_MY_USERS // the get routes filter out regular users
              case Access.WRITE => Access.WRITE_MY_USERS // we don't know the content of the request body here, routes's getAnyProblem() methods will prevent updating regular users
              case Access.CREATE => Access.CREATE_USER // we don't know the content of the request body here, routes's getAnyProblem() methods will prevent updating regular users
              case _ => access
            }
          case TOrg(_) => access match {    // a hub admin accessing an org resource
              case Access.READ => Access.READ_OTHER_ORGS
              case Access.READ_IBM_ORGS => Access.READ_IBM_ORGS
              case Access.WRITE => Access.WRITE_OTHER_ORGS
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access // this includes the case of an org admin or hub admin trying to DELETE_ORG
            }
          case TAgbot(_, _) => access match {    // a hub admin accessing an agbot
            case Access.READ => Access.READ_ALL_AGBOTS
            case Access.WRITE => Access.WRITE_ALL_AGBOTS
            case _ => access
          }
          case _ => access
        }
      } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
        Access.NOT_FOUND
      } else {  // not my org and not public, but is there or we might create it
        access match {
          case Access.READ => Access.READ_OTHER_ORGS
          case Access.WRITE => Access.WRITE_OTHER_ORGS
          case Access.WRITE_AGENT_CONFIG_MGMT => Access.WRITE_OTHER_ORGS
          case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
          case _ => access
        }
      }
    if (requiredAccess == Access.NOT_FOUND) Failure(ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
    else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
    else Failure(AccessDeniedException(accessDeniedMsg(requiredAccess, target)))
  }

  override def isAdmin: Boolean = identity.isOrgAdmin

  override def isHubAdmin: Boolean = identity.isHubAdmin

  def iOwnTarget(target: Target): Boolean = {
    if (target.mine) true
    else if (target.all) false
    else target.isOwner(this)
  }
}
