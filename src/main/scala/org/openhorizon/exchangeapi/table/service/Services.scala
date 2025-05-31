package org.openhorizon.exchangeapi.table.service

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._

import java.util.UUID


class Services(tag: Tag) extends Table[ServiceRow](tag, "services") {
  def service = column[String]("service", O.PrimaryKey)    // the content of this is orgid/service
  def orgid = column[String]("orgid")
  def owner = column[UUID]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  def documentation = column[String]("documentation")
  def url = column[String]("serviceurl")
  def version = column[String]("version")
  def arch = column[String]("arch")
  def sharable = column[String]("sharable")
  def matchHardware = column[String]("matchhardware")
  def requiredServices = column[String]("requiredservices")
  def userInput = column[String]("userinput")
  def deployment = column[String]("deployment")
  def deploymentSignature = column[String]("deploymentsignature")
  def clusterDeployment = column[String]("clusterdeployment")
  def clusterDeploymentSignature = column[String]("clusterdeploymentsignature")
  def imageStore = column[String]("imagestore")
  def lastUpdated = column[String]("lastupdated")
 
  // this describes what you get back when you return rows from a query
  def * = (service, orgid, owner, label, description, public, documentation, url, version, arch, sharable, matchHardware, requiredServices, userInput, deployment, deploymentSignature, clusterDeployment, clusterDeploymentSignature, imageStore, lastUpdated).<>(ServiceRow.tupled, ServiceRow.unapply)
  
  def user = foreignKey("user_fk", owner, UsersTQ)(_.user, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_service_fk_orgs = index(name = "idx_service_fk_orgs", on = orgid, unique = false)
  def idx_service_fk_users = index(name = "idx_service_fk_users", on = owner, unique = false)
}
