package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.{Version, VersionRange}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer


/** Contains the object representations of the DB tables related to business policies. */

final case class BService(name: String, org: String, arch: String, serviceVersions: List[BServiceVersions], nodeHealth: Option[Map[String,Int]])
final case class BServiceVersions(version: String, priority: Option[Map[String,Int]], upgradePolicy: Option[Map[String,String]])

// This is the businesspolicies table minus the key - used as the data structure to return to the REST clients
class BusinessPolicy(var owner: String, var label: String, var description: String, var service: BService, var userInput: List[OneUserInputService],var secretBinding: List[OneSecretBindingService], var properties: List[OneProperty], var constraints: List[String], var lastUpdated: String, var created: String) {
  def copy = new BusinessPolicy(owner, label, description, service, userInput,secretBinding, properties, constraints, lastUpdated, created)
}

// Note: if you add fields to this, you must also add them the update method below
final case class BusinessPolicyRow(businessPolicy: String, orgid: String, owner: String, label: String, description: String, service: String, userInput: String, secretBinding: String,properties: String, constraints: String, lastUpdated: String, created: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toBusinessPolicy: BusinessPolicy = {
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val bind: List[OneSecretBindingService] = if (secretBinding != "") read[List[OneSecretBindingService]](secretBinding) else List[OneSecretBindingService]()
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    new BusinessPolicy(owner, label, description, read[BService](service), input,bind, prop, con, lastUpdated, created)
  }

  // update returns a DB action to update this row
  //todo: we should not update the 'created' field, but we also don't want to list out all of the other fields, because it is error prone
  def update: DBIO[_] = (for { m <- BusinessPoliciesTQ.rows if m.businessPolicy === businessPolicy } yield (m.businessPolicy,m.orgid,m.owner,m.label,m.description,m.service,m.userInput,m.secretBinding,m.properties,m.constraints,m.lastUpdated)).update((businessPolicy,orgid,owner,label,description,service,userInput,secretBinding,properties,constraints,lastUpdated))

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = BusinessPoliciesTQ.rows += this
}

/** Mapping of the businesspolicies db table to a scala class */
class BusinessPolicies(tag: Tag) extends Table[BusinessPolicyRow](tag, "businesspolicies") {
  def businessPolicy = column[String]("businessPolicy", O.PrimaryKey)    // the content of this is orgid/businessPolicy
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def service = column[String]("service")
  def userInput = column[String]("userinput")
  def secretBinding = column[String]("secretbinding")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def lastUpdated = column[String]("lastupdated")
  def created = column[String]("created")
  // this describes what you get back when you return rows from a query
  def * = (businessPolicy, orgid, owner, label, description, service, userInput,secretBinding, properties, constraints, lastUpdated, created).<>(BusinessPolicyRow.tupled, BusinessPolicyRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the businesspolicies table
object BusinessPoliciesTQ {
  protected implicit val jsonFormats: Formats = DefaultFormats
  val rows = TableQuery[BusinessPolicies]

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

  def getAllBusinessPolicies(orgid: String): Query[BusinessPolicies, BusinessPolicyRow, Seq] = rows.filter(_.orgid === orgid)
  def getBusinessPolicy(businessPolicy: String): Query[BusinessPolicies, BusinessPolicyRow, Seq] = if (businessPolicy.contains("%")) rows.filter(_.businessPolicy like businessPolicy) else rows.filter(_.businessPolicy === businessPolicy)
  def getOwner(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.owner)
  def getNumOwned(owner: String): Rep[Int] = rows.filter(_.owner === owner).length
  def getLabel(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.label)
  def getDescription(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.description)
  def getService(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.service)
  def getServiceFromString(service: String): BService = read[BService](service)
  def getUserInput(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.userInput)
  def getSecretBindings(businessPolicy: String): Query[Rep[String],String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.secretBinding)
  def getLastUpdated(businessPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.businessPolicy === businessPolicy).map(_.lastUpdated)

  /** Returns a query for the specified businessPolicy attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(businessPolicy: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.businessPolicy === businessPolicy)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "service" => filter.map(_.service)
      case "userInput" => filter.map(_.userInput)
      case "secretBinding" => filter.map(_.secretBinding)
      case "properties" => filter.map(_.properties)
      case "constraints" => filter.map(_.constraints)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "created" => filter.map(_.created)
      case _ => null
    }
  }

  /** Returns the actions to delete the businessPolicy and the blockchains that reference it */
  def getDeleteActions(businessPolicy: String): DBIO[_] = getBusinessPolicy(businessPolicy).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

final case class SearchOffsetPolicyAttributes(agbot: String,
                                              offset: Option[String] = None,
                                              policy: String,
                                              session: Option[String] = None)

class SearchOffsetPolicy(tag: Tag) extends Table[SearchOffsetPolicyAttributes](tag, "search_offset_policy") {
  def agbot = column[String]("agbot")
  def offset = column[Option[String]]("offset", O.Default(None))
  def policy = column[String]("policy")
  def session = column[Option[String]]("session", O.Default(None))
  
  def pkSearchOffsetsPolicy = primaryKey("pk_searchoffsetpolicy", (agbot, policy))
  def fkAgbot = foreignKey("fk_agbot", agbot, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkPolicy = foreignKey("fk_policy", policy, BusinessPoliciesTQ.rows)(_.businessPolicy, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  
  def * = (agbot, offset, policy, session).mapTo[SearchOffsetPolicyAttributes]
}

object SearchOffsetPolicyTQ {
  val offsets = TableQuery[SearchOffsetPolicy]
  
  def dropAllOffsets(): FixedSqlAction[Int, NoStream, Effect.Write] =
    offsets.delete
  
  def getOffsetSession(agbot: String, policy: String): Query[(Rep[Option[String]], Rep[Option[String]]), (Option[String], Option[String]), Seq] =
    offsets
      .filter(_.agbot === agbot)
      .filter(_.policy === policy)
      .map(offset => (offset.offset, offset.session))
      
  def setOffsetSession(agbot: String, offset: Option[String], policy: String, session: Option[String]): FixedSqlAction[Int, NoStream, Effect.Write] =
    offsets.insertOrUpdate(SearchOffsetPolicyAttributes(agbot, offset, policy, session))
}
