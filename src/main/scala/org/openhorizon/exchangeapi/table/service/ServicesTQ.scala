package org.openhorizon.exchangeapi.table.service

import org.openhorizon.exchangeapi.auth.OrgAndId
import slick.lifted.{Query, Rep, TableQuery}
import slick.jdbc.PostgresProfile.api._

// Instance to access the services table
object ServicesTQ extends TableQuery(new Services(_)) {
  def formId(orgid: String, url: String, version: String, arch: String): String = {
    // Remove the https:// from the beginning of serviceUrl and replace troublesome chars with a dash. It has already been checked as a valid URL in validateWithMsg().
    val serviceUrl2: String = """^[A-Za-z0-9+.-]*?://""".r.replaceFirstIn(url, "")
    val serviceUrl3: String = """[$!*,;/?@&~=%]""".r.replaceAllIn(serviceUrl2, "-") // I think possible chars in valid urls are: $_.+!*,;/?:@&~=%-
    OrgAndId(orgid, serviceUrl3 + "_" + version + "_" + arch).toString
  }
  
  def getAllServices(orgid: String): Query[Services, ServiceRow, Seq] = this.filter(_.orgid === orgid)
  def getArch(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.arch)
  def getClusterDeployment(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.clusterDeployment)
  def getClusterDeploymentSignature(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.clusterDeploymentSignature)
  def getClusterServices(orgid: String): Query[Services, ServiceRow, Seq] = this.filter(r => {r.orgid === orgid && r.clusterDeployment =!= ""})
  
  /** Returns the actions to delete the service */
  def getDeleteActions(service: String): DBIO[_] = getService(service).delete
  def getDeployment(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.deployment)
  def getDeploymentSignature(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.deploymentSignature)
  def getDescription(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.description)
  def getDevicServices(orgid: String): Query[Services, ServiceRow, Seq] = this.filter(r => {r.orgid === orgid && r.deployment =!= ""})
  def getDocumentation(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.documentation)
  def getImageStore(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.imageStore)
  def getLabel(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.label)
  def getLastUpdated(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.lastUpdated)
  def getMatchHardware(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.matchHardware)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getOwner(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.owner)
  def getPublic(service: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(_.service === service).map(_.public)
  def getRequiredServices(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.requiredServices)
  def getService(service: String): Query[Services, ServiceRow, Seq] = if (service.contains("%")) this.filter(_.service like service) else this.filter(_.service === service)
  def getSharable(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.sharable)
  def getUrl(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.url)
  def getUserInput(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.userInput)
  def getVersion(service: String): Query[Rep[String], String, Seq] = this.filter(_.service === service).map(_.version)
  
  /** Returns a query for the specified service attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(service: String, attrName: String): Query[_, _, Seq] = {
    val filter = this.filter(_.service === service) // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "public" => filter.map(_.public)
      case "documentation" => filter.map(_.documentation)
      case "url" => filter.map(_.url)
      case "version" => filter.map(_.version)
      case "arch" => filter.map(_.arch)
      case "sharable" => filter.map(_.sharable)
      case "matchHardware" => filter.map(_.matchHardware)
      case "requiredServices" => filter.map(_.requiredServices)
      case "userInput" => filter.map(_.userInput)
      case "deployment" => filter.map(_.deployment)
      case "deploymentSignature" => filter.map(_.deploymentSignature)
      case "clusterDeployment" => filter.map(_.clusterDeployment)
      case "clusterDeploymentSignature" => filter.map(_.clusterDeploymentSignature)
      case "imageStore" => filter.map(_.imageStore)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }
}
