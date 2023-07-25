package org.openhorizon.exchangeapi.route.user

import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg, Identity}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatchUsersRequest(password: Option[String],
                                   admin: Option[Boolean],
                                   hubAdmin: Option[Boolean],
                                   email: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(ident: Identity, orgid: String, compositeId: String): Option[String] = {
    // Note: AuthorizationSupport.IUser does some permission checking for this route, but it can't make decisions based on the request body content,
    //        so we have to do those checks here. For example, non-root trying to create/modify root is caught there.
    // Also Note: AuthCache methods can't be used here because they aren't always up to date on every exchange instance.
    // Reminder: ident.isHubAdmin and ident.isAdmin are both true for the root user too

    if (password.isDefined && password.get == "" && !ident.isHubAdmin) Option(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    // ensure a regular user can't elevate himself to admin user, or admin user elevate to hub admin
    else if (admin.getOrElse(false) && !ident.isAdmin && !ident.isHubAdmin) Some(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    else if (hubAdmin.getOrElse(false) && !ident.isHubAdmin) Option(ExchMsg.translate("only.super.users.make.hub.admins"))
    // hub admin users have to be in the root org and org admins in a non-root org
    else if (hubAdmin.getOrElse(false) && orgid != "root") Option(ExchMsg.translate("hub.admins.in.root.org"))
    else if (admin.getOrElse(false) && orgid == "root") Option(ExchMsg.translate("user.cannot.be.in.root.org"))
    // Hub admins can only modify a user to be a hub admin or admin. This check unfortunately prevents a hub admin from changing the
    // password or email, but those are rarely done, so this is better than no check at all.
    else if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin.getOrElse(false)) Option(ExchMsg.translate("hub.admins.only.write.admins"))
    else if (admin.getOrElse(false) && hubAdmin.getOrElse(false)) Option("User cannot be admin and hubAdmin at the same time") //TODO: Translate this error message
    else None // None means no problems with input
  }

  /** Returns a tuple of the db action to update parts of the user, and the attribute name being updated. */
  def getDbUpdate(username: String, orgid: String, updatedBy: String, hashedPw: String): (DBIO[_], String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    password match {
      case Some(_) =>
        //val pw = if (Password.isHashed(password2)) password2 else Password.hash(password2)
        return ((for { u <- UsersTQ if u.username === username } yield (u.username, u.password, u.lastUpdated, u.updatedBy)).update((username, hashedPw, lastUpdated, updatedBy)), "password")
      case _ => ;
    }
    admin match { case Some(admin2) => return ((for { u <- UsersTQ if u.username === username } yield (u.username, u.admin, u.lastUpdated, u.updatedBy)).update((username, admin2, lastUpdated, updatedBy)), "admin"); case _ => ; }
    hubAdmin match { case Some(hubAdmin2) => return ((for { u <- UsersTQ if u.username === username } yield (u.username, u.hubAdmin, u.lastUpdated, u.updatedBy)).update((username, hubAdmin2, lastUpdated, updatedBy)), "hubAdmin"); case _ => ; }
    email match { case Some(email2) => return ((for { u <- UsersTQ if u.username === username } yield (u.username, u.email, u.lastUpdated, u.updatedBy)).update((username, email2, lastUpdated, updatedBy)), "email"); case _ => ; }
    (null, null)
  }
}
