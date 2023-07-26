package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.auth.Identity
import org.openhorizon.exchangeapi.utility.ExchMsg

final case class PostPutUsersRequest(password: String,
                                     admin: Boolean,
                                     hubAdmin: Option[Boolean],
                                     email: String) {
  require(password!=null && email!=null)
  def getAnyProblem(ident: Identity, orgid: String, compositeId: String, isPost: Boolean): Option[String] = {
    // Note: AuthorizationSupport.IUser does some permission checking for this route, but it can't make decisions based on the request body content,
    //        so we have to do those checks here. For example, non-root trying to create/modify root is caught there.
    // Also Note: AuthCache methods can't be used here because they aren't always up to date on every exchange instance.
    // Reminder: ident.isHubAdmin and ident.isAdmin are both true for the root user too

    if ((password == "") && !ident.isHubAdmin) Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
    // ensure a regular user can't elevate himself to admin user, or admin user elevate to hub admin
    else if (admin && !ident.isAdmin && !ident.isHubAdmin) Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    else if (hubAdmin.getOrElse(false) && !ident.isHubAdmin) Option(ExchMsg.translate("only.super.users.make.hub.admins"))
    // hub admin users have to be in the root org and org admins or regular users in a non-root org
    else if (hubAdmin.getOrElse(false) && orgid != "root") Option(ExchMsg.translate("hub.admins.in.root.org"))
    else if (!hubAdmin.getOrElse(false) && orgid == "root") Option(ExchMsg.translate("user.cannot.be.in.root.org"))
    // hub admins can only create hub admins or admins, or modify a user to be a hub admin or admin
    else if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin) Option(ExchMsg.translate("hub.admins.only.write.admins"))
    else if (admin && hubAdmin.getOrElse(false)) Option("User cannot be admin and hubAdmin at the same time") //TODO: Translate this error message
    else None // None means no problems with input
  }
}
