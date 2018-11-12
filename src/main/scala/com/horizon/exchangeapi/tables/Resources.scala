package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.{ApiTime, OrgAndId}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to resources. */
case class ResourceRef(org: String, url: String, version: String, arch: String)

// This is the resource table minus the key - used as the data structure to return to the REST clients
class Resource(var owner: String, var name: String, var description: String, var public: Boolean, var documentation: String, var version: String, var arch: String, var sharable: String, var deployment: String, var deploymentSignature: String, var resourceStore: Map[String,Any], var lastUpdated: String) {
  def copy = new Resource(owner, name, description, public, documentation, version, arch, sharable, deployment, deploymentSignature, resourceStore, lastUpdated)
}

case class ResourceRow(resource: String, orgid: String, owner: String, name: String, description: String, public: Boolean, documentation: String, version: String, arch: String, sharable: String, deployment: String, deploymentSignature: String, resourceStore: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toResource: Resource = {
    val p = if (resourceStore != "") read[Map[String,Any]](resourceStore) else Map[String,Any]()
    new Resource(owner, name, description, public, documentation, version, arch, sharable, deployment, deploymentSignature, p, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- ResourcesTQ.rows if m.resource === resource } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ResourcesTQ.rows += this
}

/** Mapping of the resources db table to a scala class */
class Resources(tag: Tag) extends Table[ResourceRow](tag, "resources") {
  def resource = column[String]("resource", O.PrimaryKey)    // the content of this is orgid/name_version
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def name = column[String]("name")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  def documentation = column[String]("documentation")
  def version = column[String]("version")
  def arch = column[String]("arch")
  def sharable = column[String]("sharable")
  def deployment = column[String]("deployment")
  def deploymentSignature = column[String]("deploymentsignature")
  def resourceStore = column[String]("resourceStore")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (resource, orgid, owner, name, description, public, documentation, version, arch, sharable, deployment, deploymentSignature, resourceStore, lastUpdated) <> (ResourceRow.tupled, ResourceRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the resources table
object ResourcesTQ {
  val rows = TableQuery[Resources]

  def formId(orgid: String, name: String, version: String): String = {
    // Replace troublesome chars with a dash. It has already been checked as a valid URL in validate().
    val resourceUrl2 = """[$!*,;/?@&~=%]""".r replaceAllIn (name, "-")     // I think possible chars in valid urls are: $_.+!*,;/?:@&~=%-
    return OrgAndId(orgid, resourceUrl2 + "_" + version).toString
  }

  def getAllResources(orgid: String) = rows.filter(_.orgid === orgid)
  def getResource(resource: String) = rows.filter(_.resource === resource)
  def getOwner(resource: String) = rows.filter(_.resource === resource).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getName(resource: String) = rows.filter(_.resource === resource).map(_.name)
  def getDescription(resource: String) = rows.filter(_.resource === resource).map(_.description)
  def getPublic(resource: String) = rows.filter(_.resource === resource).map(_.public)
  def getDocumentation(resource: String) = rows.filter(_.resource === resource).map(_.documentation)
  def getVersion(resource: String) = rows.filter(_.resource === resource).map(_.version)
  def getArch(resource: String) = rows.filter(_.resource === resource).map(_.arch)
  def getSharable(resource: String) = rows.filter(_.resource === resource).map(_.sharable)
  def getDeployment(resource: String) = rows.filter(_.resource === resource).map(_.deployment)
  def getDeploymentSignature(resource: String) = rows.filter(_.resource === resource).map(_.deploymentSignature)
  def getResourceStore(resource: String) = rows.filter(_.resource === resource).map(_.resourceStore)
  def getLastUpdated(resource: String) = rows.filter(_.resource === resource).map(_.lastUpdated)

  /** Returns a query for the specified resource attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(resource: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.resource === resource)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "owner" => filter.map(_.owner)
      case "name" => filter.map(_.name)
      case "description" => filter.map(_.description)
      case "public" => filter.map(_.public)
      case "documentation" => filter.map(_.documentation)
      case "version" => filter.map(_.version)
      case "arch" => filter.map(_.arch)
      case "sharable" => filter.map(_.sharable)
      case "deployment" => filter.map(_.deployment)
      case "deploymentSignature" => filter.map(_.deploymentSignature)
      case "resourceStore" => filter.map(_.resourceStore)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the resource */
  def getDeleteActions(resource: String): DBIO[_] = getResource(resource).delete
}


// Key is a sub-resource of resource
case class ResourceKeyRow(keyId: String, resourceId: String, key: String, lastUpdated: String) {
  def toResourceKey = ResourceKey(key, lastUpdated)

  def upsert: DBIO[_] = ResourceKeysTQ.rows.insertOrUpdate(this)
}

class ResourceKeys(tag: Tag) extends Table[ResourceKeyRow](tag, "resourcekeys") {
  def keyId = column[String]("keyid")     // key - the key name
  def resourceId = column[String]("resourceid")               // additional key - the composite orgid/resourceid
  def key = column[String]("key")                   // the actual key content
  def lastUpdated = column[String]("lastupdated")
  def * = (keyId, resourceId, key, lastUpdated) <> (ResourceKeyRow.tupled, ResourceKeyRow.unapply)
  def primKey = primaryKey("pk_resk", (keyId, resourceId))
  def resource = foreignKey("resource_fk", resourceId, ResourcesTQ.rows)(_.resource, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object ResourceKeysTQ {
  val rows = TableQuery[ResourceKeys]

  def getKeys(resourceId: String) = rows.filter(_.resourceId === resourceId)
  def getKey(resourceId: String, keyId: String) = rows.filter( r => {r.resourceId === resourceId && r.keyId === keyId} )
}

case class ResourceKey(key: String, lastUpdated: String)


// Auth is a sub-resource of resource
case class ResourceAuthRow(authId: Int, resourceId: String, username: String, token: String, lastUpdated: String) {
  def toResourceAuth = ResourceAuth(authId, username, token, lastUpdated)

  // The returning operator is necessary on insert to have it return the id auto-generated, instead of the number of rows inserted
  def insert: DBIO[_] = (ResourceAuthsTQ.rows returning ResourceAuthsTQ.rows.map(_.authId)) += this
  def update: DBIO[_] = ResourceAuthsTQ.getAuth(resourceId, authId).update(this)
}

class ResourceAuths(tag: Tag) extends Table[ResourceAuthRow](tag, "resourceauths") {
  def authId = column[Int]("authid", O.PrimaryKey, O.AutoInc)     // auth - the generated id for this resource
  def resourceId = column[String]("resourceid")               // additional key - the composite orgid/resourceid
  def username = column[String]("username")                   // the type of token, usually 'token' or 'iamapikey'
  def token = column[String]("token")                   // the actual token content
  def lastUpdated = column[String]("lastupdated")
  def * = (authId, resourceId, username, token, lastUpdated) <> (ResourceAuthRow.tupled, ResourceAuthRow.unapply)
  //def primKey = primaryKey("pk_authk", (authId, resourceId))    // <- the auto-created id is already unique
  def resource = foreignKey("resource_fk", resourceId, ResourcesTQ.rows)(_.resource, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object ResourceAuthsTQ {
  val rows = TableQuery[ResourceAuths]

  def getAuths(resourceId: String) = rows.filter(_.resourceId === resourceId)
  def getAuth(resourceId: String, authId: Int) = rows.filter( r => {r.resourceId === resourceId && r.authId === authId} )
  def getDupAuth(resourceId: String, username: String, token: String) = rows.filter( r => {r.resourceId === resourceId && r.username === username && r.token === token} )
  def getLastUpdatedAction(resourceId: String, authId: Int) = rows.filter( r => {r.resourceId === resourceId && r.authId === authId} ).map(_.lastUpdated).update(ApiTime.nowUTC)
}

case class ResourceAuth(authId: Int, username: String, token: String, lastUpdated: String)
