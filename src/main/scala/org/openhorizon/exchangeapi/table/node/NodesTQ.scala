package org.openhorizon.exchangeapi.table.node

import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.{OneUserInputService, ServiceRef2, ServicesTQ}
import org.openhorizon.exchangeapi.{Version, VersionRange}
import slick.dbio.{DBIO, Effect, NoStream}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer

// Instance to access the nodes table
// object nodes extends TableQuery(new Nodes(_)) {
//   def listUserNodes(username: String) = this.filter(_.owner === username)
// }
object NodesTQ  extends TableQuery(new Nodes(_)){
  // Build a list of db actions to verify that the referenced services exist
  // Note: this currently doesn't check the registeredServices because only the agent creates that, and its content might be changing
  def validateServiceIds(userInput: List[OneUserInputService]): (DBIO[Vector[Int]], Vector[ServiceRef2]) = {
    val actions: ListBuffer[DBIO[Int]] = ListBuffer[DBIO[Int]]()
    val svcRefs: ListBuffer[ServiceRef2] = ListBuffer[ServiceRef2]()
    // Go thru the services referenced in the userInput section
    for (s <- userInput) {
      svcRefs += ServiceRef2(s.serviceUrl, s.serviceOrgid, s.serviceVersionRange.getOrElse("[0.0.0,INFINITY)"), s.serviceArch.getOrElse(""))  // the service ref is just for reporting bad input errors
      val arch: String = if (s.serviceArch.isEmpty || s.serviceArch.get == "") "%" else s.serviceArch.get
      //perf: the best we can do is use the version if the range is a single version, otherwise use %
      val svc: String = if (s.serviceVersionRange.getOrElse("") == "") "%"
      else {
        val singleVer: Option[Version] = VersionRange(s.serviceVersionRange.get).singleVersion
        if (singleVer.isDefined) singleVer.toString
        else "%"
      }
      val svcId: String = ServicesTQ.formId(s.serviceOrgid, s.serviceUrl, svc, arch)
      actions += ServicesTQ.getService(svcId).length.result
    }
    (DBIO.sequence(actions.toVector), svcRefs.toVector)      // convert the list of actions to a DBIO sequence
  }

  def getAllNodes(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(_.orgid === orgid)
  def getAllNodesId(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.id)
  def getNodeTypeNodes(orgid: String, nodeType: String): Query[Nodes, NodeRow, Seq] = this.filter(r => {r.orgid === orgid && r.nodeType === nodeType})
  def getNonPatternNodes(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(r => {r.orgid === orgid && r.pattern === ""})
  def getRegisteredNodesInOrg(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(node => {node.orgid === orgid && node.publicKey =!= ""})
  def getNode(id: String): Query[Nodes, NodeRow, Seq] = this.filter(_.id === id)
  def getToken(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.token)
  def getOwner(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.owner)
  def getRegisteredServices(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.regServices)
  def getUserInput(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.userInput)
  def getNodeType(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.nodeType)
  def getPattern(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.pattern)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getLastHeartbeat(id: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.publicKey)
  def getArch(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.arch)
  def getHeartbeatIntervals(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.heartbeatIntervals)
  def getLastUpdated(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.lastUpdated)
  def getNodeUsingPolicy(id: String): Query[(Rep[String], Rep[String]), (String, String), Seq] = this.filter(_.id === id).map(x => (x.pattern, x.publicKey))

  def setLastHeartbeat(id: String, lastHeartbeat: String): FixedSqlAction[Int, NoStream, Effect.Write] = this.filter(_.id === id).map(_.lastHeartbeat).update(Option(lastHeartbeat))
  def setLastUpdated(id: String, lastUpdated: String): FixedSqlAction[Int, NoStream, Effect.Write] = this.filter(_.id === id).map(_.lastUpdated).update(lastUpdated)


  /** Returns a query for the specified node attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_, _, Seq] = {
    val filter = this.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName.toLowerCase() match {
      case "arch" => filter.map(_.arch)
      case "clusternamespace" => filter.map(_.clusterNamespace.getOrElse(""))
      case "ha_group" => NodeGroupTQ.filter(_.group in NodeGroupAssignmentTQ.filter(_.node === id).map(_.group)).map(_.name)
      case "isNamespaceScoped" => filter.map(_.isNamespaceScoped)
      case "heartbeatintervals" => filter.map(_.heartbeatIntervals)
      case "lastheartbeat" => filter.map(_.lastHeartbeat)
      case "lastupdated" => filter.map(_.lastUpdated)
      case "msgendpoint" => filter.map(_.msgEndPoint)
      case "name" => filter.map(_.name)
      case "nodetype" => filter.map(_.nodeType)
      case "owner" => filter.map(_.owner)
      case "pattern" => filter.map(_.pattern)
      case "publickey" => filter.map(_.publicKey)
      case "regservices" => filter.map(_.regServices)
      case "softwareversions" => filter.map(_.softwareVersions)
      case "token" => filter.map(_.token)
      case "userinput" => filter.map(_.userInput)
      case _ => null
    }
  }

  /* Not needed anymore, because node properties are no longer in a separate table that needs to be joined...
  Separate the join of the nodes and properties tables into their respective scala classes (collapsing duplicates) and return a hash containing it all.
    * Note: this can also be used when querying node rows that have services, because the services are faithfully preserved in the Node object.
  def parseJoin(isSuperUser: Boolean, list: Seq[NodeRow] ): Map[String,Node] = {
    // Separate the partially duplicate join rows into maps that only keep unique values
    val nodes = new MutableHashMap[String,Node]    // the key is node id
    for (d <- list) {
      d.putInHashMap(isSuperUser, nodes)
    }
    nodes.toMap
  }
  */
}
