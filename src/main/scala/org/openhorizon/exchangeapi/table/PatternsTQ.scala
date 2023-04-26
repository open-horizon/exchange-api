package org.openhorizon.exchangeapi.table

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.{Version, VersionRange}
import slick.dbio.DBIO
import slick.lifted.{Query, Rep, TableQuery}
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.ListBuffer

// Instance to access the patterns table
object PatternsTQ  extends TableQuery(new Patterns(_)){
  protected implicit val jsonFormats: Formats = DefaultFormats

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds(services: List[PServices], userInput: List[OneUserInputService]): (DBIO[Vector[Int]], Vector[ServiceRef2]) = {
    // Currently, anax does not support a pattern with no services, so do not support that here
    val actions: ListBuffer[DBIO[Int]] = ListBuffer[DBIO[Int]]()
    val svcRefs: ListBuffer[ServiceRef2] = ListBuffer[ServiceRef2]()
    // First go thru the services the pattern deploys
    for (s <- services) {
      for (sv <- s.serviceVersions) {
        svcRefs += ServiceRef2(s.serviceUrl, s.serviceOrgid, sv.version, s.serviceArch)   // the service ref is just for reporting bad input errors
        val svcId: String = ServicesTQ.formId(s.serviceOrgid, s.serviceUrl, sv.version, s.serviceArch)
        actions += ServicesTQ.getService(svcId).length.result
      }
    }
    // Now go thru the services referenced in the userInput section
    for (s <- userInput) {
      svcRefs += ServiceRef2(s.serviceUrl, s.serviceOrgid, s.serviceVersionRange.getOrElse("[0.0.0,INFINITY)"), s.serviceArch.getOrElse(""))  // the service ref is just for reporting bad input errors
      val arch: String = if (s.serviceArch.isEmpty || s.serviceArch.get == "") "%" else s.serviceArch.get
      //someday: the best we can do is use the version if the range is a single version, otherwise use %
      val svc: String = if (s.serviceVersionRange.getOrElse("") == "") "%"
        else {
          val singleVer: Option[Version] = VersionRange(s.serviceVersionRange.get).singleVersion
          if (singleVer.isDefined) singleVer.toString
          else "%"
        }
      val svcId: String = ServicesTQ.formId(s.serviceOrgid, s.serviceUrl, svc, arch)
      actions += ServicesTQ.getService(svcId).length.result
    }
    //return DBIO.seq(actions: _*)      // convert the list of actions to a DBIO seq
    (DBIO.sequence(actions.toVector), svcRefs.toVector)      // convert the list of actions to a DBIO sequence
  }

  def getAllPatterns(orgid: String): Query[Patterns, PatternRow, Seq] = this.filter(_.orgid === orgid)
  def getPattern(pattern: String): Query[Patterns, PatternRow, Seq] = if (pattern.contains("%")) this.filter(_.pattern like pattern) else this.filter(_.pattern === pattern)
  def getOwner(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.owner)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getLabel(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.label)
  def getDescription(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.description)
  def getPublic(pattern: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(_.pattern === pattern).map(_.public)
  def getServices(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.services)
  def getServicesFromString(services: String): List[PServices] = if (services == "") List[PServices]() else read[List[PServices]](services)
  def getUserInput(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.userInput)
  def getSecretBindings(pattern: String): Query[Rep[String],String, Seq] = this.filter(_.pattern === pattern).map(_.secretBinding)
  def getAgreementProtocols(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.agreementProtocols)
  def getLastUpdated(pattern: String): Query[Rep[String], String, Seq] = this.filter(_.pattern === pattern).map(_.lastUpdated)

  /** Returns a query for the specified pattern attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(pattern: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.pattern === pattern)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName.toLowerCase() match {
      case "agreementprotocols" => filter.map(_.agreementProtocols)
      case "clusternamespace" => filter.map(_.clusterNamespace.getOrElse(""))
      case "description" => filter.map(_.description)
      case "label" => filter.map(_.label)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "owner" => filter.map(_.owner)
      case "public" => filter.map(_.public)
      case "secretbinding" => filter.map(_.secretBinding)
      case "services" => filter.map(_.services)
      case "userinput" => filter.map(_.userInput)
      case _ => null
    }
  }

  /** Returns the actions to delete the pattern and the blockchains that reference it */
  def getDeleteActions(pattern: String): DBIO[_] = getPattern(pattern).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}
