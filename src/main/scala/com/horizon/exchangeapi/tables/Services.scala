package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.{ApiTime, OrgAndId}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to services. */
case class ServiceRef(url: String, org: String, version: String, versionRange: String, arch: String)

// This is the service table minus the key - used as the data structure to return to the REST clients
class Service(var owner: String, var label: String, var description: String, var public: Boolean, var documentation: String, var url: String, var version: String, var versionRange: String, var arch: String, var sharable: String, var matchHardware: Map[String,Any], var requiredServices: List[ServiceRef], var userInput: List[Map[String,String]], var deployment: String, var deploymentSignature: String, var imageStore: Map[String,Any], var lastUpdated: String) {
  def copy = new Service(owner, label, description, public, documentation, url, version, versionRange, arch, sharable, matchHardware, requiredServices, userInput, deployment, deploymentSignature, imageStore, lastUpdated)
}

case class ServiceRow(service: String, orgid: String, owner: String, label: String, description: String, public: Boolean, documentation: String, url: String, version: String, versionRange: String, arch: String, sharable: String, matchHardware: String, requiredServices: String, userInput: String, deployment: String, deploymentSignature: String, imageStore: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toService: Service = {
    val mh = if (matchHardware != "") read[Map[String,Any]](matchHardware) else Map[String,Any]()
    val rs = if (requiredServices != "") read[List[ServiceRef]](requiredServices) else List[ServiceRef]()
    val input = if (userInput != "") read[List[Map[String,String]]](userInput) else List[Map[String,String]]()
    val p = if (imageStore != "") read[Map[String,Any]](imageStore) else Map[String,Any]()
    new Service(owner, label, description, public, documentation, url, version, versionRange, arch, sharable, mh, rs, input, deployment, deploymentSignature, p, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- ServicesTQ.rows if m.service === service } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ServicesTQ.rows += this
}

/** Mapping of the services db table to a scala class */
class Services(tag: Tag) extends Table[ServiceRow](tag, "services") {
  def service = column[String]("service", O.PrimaryKey)    // the content of this is orgid/service
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  def documentation = column[String]("documentation")
  def url = column[String]("serviceurl")
  def version = column[String]("version")
  def versionRange = column[String]("versionRange")
  def arch = column[String]("arch")
  def sharable = column[String]("sharable")
  def matchHardware = column[String]("matchhardware")
  def requiredServices = column[String]("requiredservices")
  def userInput = column[String]("userinput")
  def deployment = column[String]("deployment")
  def deploymentSignature = column[String]("deploymentsignature")
  def imageStore = column[String]("imagestore")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (service, orgid, owner, label, description, public, documentation, url, version, arch, sharable, matchHardware, requiredServices, userInput, deployment, deploymentSignature, imageStore, lastUpdated) <> (ServiceRow.tupled, ServiceRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the services table
object ServicesTQ {
  val rows = TableQuery[Services]

  def formId(orgid: String, url: String, version: String, versionRange: String, arch: String): String = {
    // Remove the https:// from the beginning of serviceUrl and replace troublesome chars with a dash. It has already been checked as a valid URL in validate().
    val serviceUrl2 = """^[A-Za-z0-9+.-]*?://""".r replaceFirstIn (url, "")
    val serviceUrl3 = """[$!*,;/?@&~=%]""".r replaceAllIn (serviceUrl2, "-")     // I think possible chars in valid urls are: $_.+!*,;/?:@&~=%-
    return OrgAndId(orgid, serviceUrl3 + "_" + version + "_" + arch).toString
  }

  def getAllServices(orgid: String) = rows.filter(_.orgid === orgid)
  def getService(service: String) = if (service.contains("%")) rows.filter(_.service like service) else rows.filter(_.service === service)
  def getOwner(service: String) = rows.filter(_.service === service).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLabel(service: String) = rows.filter(_.service === service).map(_.label)
  def getDescription(service: String) = rows.filter(_.service === service).map(_.description)
  def getPublic(service: String) = rows.filter(_.service === service).map(_.public)
  def getDocumentation(service: String) = rows.filter(_.service === service).map(_.documentation)
  def getUrl(service: String) = rows.filter(_.service === service).map(_.url)
  def getVersion(service: String) = rows.filter(_.service === service).map(_.version)
  def getVersionRange(service: String) = rows.filter(_.service === service).map(_.versionRange)
  def getArch(service: String) = rows.filter(_.service === service).map(_.arch)
  def getSharable(service: String) = rows.filter(_.service === service).map(_.sharable)
  def getMatchHardware(service: String) = rows.filter(_.service === service).map(_.matchHardware)
  def getRequiredServices(service: String) = rows.filter(_.service === service).map(_.requiredServices)
  def getUserInput(service: String) = rows.filter(_.service === service).map(_.userInput)
  def getDeployment(service: String) = rows.filter(_.service === service).map(_.deployment)
  def getDeploymentSignature(service: String) = rows.filter(_.service === service).map(_.deploymentSignature)
  def getImageStore(service: String) = rows.filter(_.service === service).map(_.imageStore)
  def getLastUpdated(service: String) = rows.filter(_.service === service).map(_.lastUpdated)

  /** Returns a query for the specified service attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(service: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.service === service)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "public" => filter.map(_.public)
      case "documentation" => filter.map(_.documentation)
      case "url" => filter.map(_.url)
      case "version" => filter.map(_.version)
      case "versionRange" => filter.map(_.versionRange)
      case "arch" => filter.map(_.arch)
      case "sharable" => filter.map(_.sharable)
      case "matchHardware" => filter.map(_.matchHardware)
      case "requiredServices" => filter.map(_.requiredServices)
      case "userInput" => filter.map(_.userInput)
      case "deployment" => filter.map(_.deployment)
      case "deploymentSignature" => filter.map(_.deploymentSignature)
      case "imageStore" => filter.map(_.imageStore)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the service */
  def getDeleteActions(service: String): DBIO[_] = getService(service).delete
}


// Policy is a sub-resource of service
case class OneProperty(name: String, `type`: Option[String], value: Any)

case class ServicePolicyRow(serviceId: String, properties: String, constraints: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toServicePolicy: ServicePolicy = {
    val prop = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con = if (constraints != "") read[List[String]](constraints) else List[String]()
    return ServicePolicy(prop, con, lastUpdated)
  }

  def upsert: DBIO[_] = ServicePolicyTQ.rows.insertOrUpdate(this)
}

class ServicePolicies(tag: Tag) extends Table[ServicePolicyRow](tag, "servicepolicies") {
  def serviceId = column[String]("serviceid", O.PrimaryKey)    // the content of this is orgid/service
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def lastUpdated = column[String]("lastUpdated")
  def * = (serviceId, properties, constraints, lastUpdated) <> (ServicePolicyRow.tupled, ServicePolicyRow.unapply)
  def service = foreignKey("service_fk", serviceId, ServicesTQ.rows)(_.service, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}

object ServicePolicyTQ {
  val rows = TableQuery[ServicePolicies]
  def getServicePolicy(serviceId: String) = rows.filter(_.serviceId === serviceId)
}

case class ServicePolicy(properties: List[OneProperty], constraints: List[String], lastUpdated: String)


// Key is a sub-resource of service
case class ServiceKeyRow(keyId: String, serviceId: String, key: String, lastUpdated: String) {
  def toServiceKey = ServiceKey(key, lastUpdated)

  def upsert: DBIO[_] = ServiceKeysTQ.rows.insertOrUpdate(this)
}

class ServiceKeys(tag: Tag) extends Table[ServiceKeyRow](tag, "servicekeys") {
  def keyId = column[String]("keyid")     // key - the key name
  def serviceId = column[String]("serviceid")               // additional key - the composite orgid/serviceid
  def key = column[String]("key")                   // the actual key content
  def lastUpdated = column[String]("lastupdated")
  def * = (keyId, serviceId, key, lastUpdated) <> (ServiceKeyRow.tupled, ServiceKeyRow.unapply)
  def primKey = primaryKey("pk_svck", (keyId, serviceId))
  def service = foreignKey("service_fk", serviceId, ServicesTQ.rows)(_.service, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object ServiceKeysTQ {
  val rows = TableQuery[ServiceKeys]

  def getKeys(serviceId: String) = rows.filter(_.serviceId === serviceId)
  def getKey(serviceId: String, keyId: String) = rows.filter( r => {r.serviceId === serviceId && r.keyId === keyId} )
}

case class ServiceKey(key: String, lastUpdated: String)


// DockAuth is a sub-resource of service
case class ServiceDockAuthRow(dockAuthId: Int, serviceId: String, registry: String, username: String, token: String, lastUpdated: String) {
  def toServiceDockAuth = ServiceDockAuth(dockAuthId, registry, username, token, lastUpdated)

  // The returning operator is necessary on insert to have it return the id auto-generated, instead of the number of rows inserted
  def insert: DBIO[_] = (ServiceDockAuthsTQ.rows returning ServiceDockAuthsTQ.rows.map(_.dockAuthId)) += this
  def update: DBIO[_] = ServiceDockAuthsTQ.getDockAuth(serviceId, dockAuthId).update(this)
}

class ServiceDockAuths(tag: Tag) extends Table[ServiceDockAuthRow](tag, "servicedockauths") {
  def dockAuthId = column[Int]("dockauthid", O.PrimaryKey, O.AutoInc)     // dockAuth - the generated id for this resource
  def serviceId = column[String]("serviceid")               // additional key - the composite orgid/serviceid
  def registry = column[String]("registry")                   // the docker registry this token is for
  def username = column[String]("username")                   // the type of token, usually 'token' or 'iamapikey'
  def token = column[String]("token")                   // the actual token content
  def lastUpdated = column[String]("lastupdated")
  def * = (dockAuthId, serviceId, registry, username, token, lastUpdated) <> (ServiceDockAuthRow.tupled, ServiceDockAuthRow.unapply)
  //def primKey = primaryKey("pk_svck", (dockAuthId, serviceId))    // <- the auto-created id is already unique
  def service = foreignKey("service_fk", serviceId, ServicesTQ.rows)(_.service, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object ServiceDockAuthsTQ {
  val rows = TableQuery[ServiceDockAuths]

  def getDockAuths(serviceId: String) = rows.filter(_.serviceId === serviceId)
  def getDockAuth(serviceId: String, dockAuthId: Int) = rows.filter( r => {r.serviceId === serviceId && r.dockAuthId === dockAuthId} )
  def getDupDockAuth(serviceId: String, registry: String, username: String, token: String) = rows.filter( r => {r.serviceId === serviceId && r.registry === registry && r.username === username && r.token === token} )
  def getLastUpdatedAction(serviceId: String, dockAuthId: Int) = rows.filter( r => {r.serviceId === serviceId && r.dockAuthId === dockAuthId} ).map(_.lastUpdated).update(ApiTime.nowUTC)
}

case class ServiceDockAuth(dockAuthId: Int, registry: String, username: String, token: String, lastUpdated: String)
