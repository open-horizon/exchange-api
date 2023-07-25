package org.openhorizon.exchangeapi.table.deploymentpolicy

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.table.service.{ServiceRef2, ServicesTQ}
import org.openhorizon.exchangeapi.{Version, VersionRange}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

import scala.collection.mutable.ListBuffer

// Instance to access the businesspolicies table
object BusinessPoliciesTQ extends TableQuery(new BusinessPolicies(_)) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds(service: BService, userInput: List[OneUserInputService]): (DBIO[Vector[Int]], Vector[ServiceRef2]) = {
    val actions: ListBuffer[DBIO[Int]] = ListBuffer[DBIO[Int]]()
    val svcRefs: ListBuffer[ServiceRef2] = ListBuffer[ServiceRef2]()
    // First go thru the services the business policy refers to. We only support the case in which the service isn't specified for patch
    for (sv <- service.serviceVersions) {
      svcRefs += ServiceRef2(service.name, service.org, sv.version, service.arch)
      val arch: String = if (service.arch == "*" || service.arch == "") "%" else service.arch   // handle arch=* so we can do a like on the resulting svcId
      val svcId: String = ServicesTQ.formId(service.org, service.name, sv.version, arch)
      actions += ServicesTQ.getService(svcId).length.result
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
    (DBIO.sequence(actions.toVector), svcRefs.toVector)      // convert the list of actions to a DBIO sequence
  }

  def getAllBusinessPolicies(orgid: String): Query[BusinessPolicies, BusinessPolicyRow, Seq] = this.filter(_.orgid === orgid)
  def getBusinessPolicy(businessPolicy: String): Query[BusinessPolicies, BusinessPolicyRow, Seq] = if (businessPolicy.contains("%")) this.filter(_.businessPolicy like businessPolicy) else this.filter(_.businessPolicy === businessPolicy)
  def getDescription(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.description)
  def getLabel(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.label)
  def getLastUpdated(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.lastUpdated)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getOwner(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.owner)
  def getSecretBindings(businessPolicy: String): Query[Rep[String],String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.secretBinding)
  def getService(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.service)
  def getServiceFromString(service: String): BService = read[BService](service)
  def getUserInput(businessPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.businessPolicy === businessPolicy).map(_.userInput)

  /** Returns a query for the specified businessPolicy attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(businessPolicy: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.businessPolicy === businessPolicy)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName.toLowerCase match {
      case "constraints" => filter.map(_.constraints)
      case "created" => filter.map(_.created)
      case "description" => filter.map(_.description)
      case "label" => filter.map(_.label)
      case "lastupdated" => filter.map(_.lastUpdated)
      case "owner" => filter.map(_.owner)
      case "properties" => filter.map(_.properties)
      case "secretbinding" => filter.map(_.secretBinding)
      case "service" => filter.map(_.service)
      case "userinput" => filter.map(_.userInput)
      case _ => null
    }
  }

  /** Returns the actions to delete the businessPolicy and the blockchains that reference it */
  def getDeleteActions(businessPolicy: String): DBIO[_] = getBusinessPolicy(businessPolicy).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}
