package org.openhorizon.exchangeapi.table.node

import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._

import java.util.UUID

/** Mapping of the nodes db table to a scala class */
class Nodes(tag: Tag) extends Table[NodeRow](tag, "nodes") {
  def id = column[String]("id", O.PrimaryKey)   // in the form org/nodeid
  def orgid = column[String]("orgid")
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[UUID]("owner")  // root is the default because during upserts by root, we do not want root to take over the node if it already exists
  def nodeType = column[String]("nodetype")
  def pattern = column[String]("pattern")       // this is orgid/patternname
  def regServices = column[String]("regservices")
  def userInput = column[String]("userinput")
  def msgEndPoint = column[String]("msgendpoint")
  def softwareVersions = column[String]("swversions")
  def publicKey = column[String]("publickey")     // this is last because that is where alter table in upgradedb puts it
  def lastHeartbeat = column[Option[String]]("lastheartbeat")
  def arch = column[String]("arch")
  def heartbeatIntervals = column[String]("heartbeatintervals")
  def lastUpdated = column[String]("lastupdated")
  def clusterNamespace = column[Option[String]]("cluster_namespace")
  def isNamespaceScoped = column[Boolean]("is_namespace_scoped", O.Default(false))

  // this describes what you get back when you return this.from a query
  def * = (id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped).mapTo[NodeRow]
  def user = foreignKey("user_fk", owner, UsersTQ)(_.user, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  //def patKey = foreignKey("pattern_fk", pattern, PatternsTQ)(_.pattern, onUpdate=ForeignKeyAction.Cascade)     // <- we can't make this a foreign key because it is optional
}
