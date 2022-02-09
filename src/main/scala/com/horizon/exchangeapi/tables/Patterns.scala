package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.{Version, VersionRange}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.ListBuffer


/** Contains the object representations of the DB tables related to patterns. */

final case class PServices(serviceUrl: String, serviceOrgid: String, serviceArch: String, agreementLess: Option[Boolean], serviceVersions: List[PServiceVersions], dataVerification: Option[Map[String,Any]], nodeHealth: Option[Map[String,Int]])
final case class PServiceVersions(version: String, deployment_overrides: Option[String], deployment_overrides_signature: Option[String], priority: Option[Map[String,Int]], upgradePolicy: Option[Map[String,String]])
final case class PDataVerification(enabled: Boolean, URL: String, user: String, password: String, interval: Int, check_rate: Int, metering: Map[String,Any])

// These classes are also used by business policies and nodes
final case class OneUserInputService(serviceOrgid: String, serviceUrl: String, serviceArch: Option[String], serviceVersionRange: Option[String], inputs: List[OneUserInputValue])
final case class OneUserInputValue(name: String, value: Any)

final case class OneSecretBindingService(serviceOrgid: String, serviceUrl: String, serviceArch: Option[String], serviceVersionRange: Option[String], secrets: List[Map[String, String]])
// This is the pattern table minus the key - used as the data structure to return to the REST clients
class Pattern(var owner: String, var label: String, var description: String, var public: Boolean, var services: List[PServices], var userInput: List[OneUserInputService], var secretBinding: List[OneSecretBindingService],var agreementProtocols: List[Map[String,String]], var lastUpdated: String) {
  def copy = new Pattern(owner, label, description, public, services, userInput, secretBinding, agreementProtocols, lastUpdated)
}

final case class PatternRow(pattern: String, orgid: String, owner: String, label: String, description: String, public: Boolean, services: String, userInput: String, secretBinding: String, agreementProtocols: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toPattern: Pattern = {
    val svc: List[PServices] = if (services == "") List[PServices]() else read[List[PServices]](services)
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val bind: List[OneSecretBindingService] = if (secretBinding != "") read[List[OneSecretBindingService]](secretBinding) else List[OneSecretBindingService]()
    val agproto: List[Map[String, String]] = if (agreementProtocols != "") read[List[Map[String,String]]](agreementProtocols) else List[Map[String,String]]()
    new Pattern(owner, label, description, public, svc, input, bind, agproto,lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- PatternsTQ if m.pattern === pattern } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = PatternsTQ += this
}

/** Mapping of the patterns db table to a scala class */
class Patterns(tag: Tag) extends Table[PatternRow](tag, "patterns") {
  def pattern = column[String]("pattern", O.PrimaryKey)    // the content of this is orgid/pattern
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  def services = column[String]("services")
  def userInput = column[String]("userinput")
  def secretBinding = column[String]("secretbinding")
  def agreementProtocols = column[String]("agreementProtocols")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (pattern, orgid, owner, label, description, public, services, userInput,secretBinding, agreementProtocols, lastUpdated).<>(PatternRow.tupled, PatternRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

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
    attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "public" => filter.map(_.public)
      case "services" => filter.map(_.services)
      case "userInput" => filter.map(_.userInput)
      case "secretBinding" => filter.map(_.secretBinding)
      case "agreementProtocols" => filter.map(_.agreementProtocols)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the pattern and the blockchains that reference it */
  def getDeleteActions(pattern: String): DBIO[_] = getPattern(pattern).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}


// Key is a sub-resource of pattern
final case class PatternKeyRow(keyId: String, patternId: String, key: String, lastUpdated: String) {
  def toPatternKey: PatternKey = PatternKey(key, lastUpdated)

  def upsert: DBIO[_] = PatternKeysTQ.insertOrUpdate(this)
}

class PatternKeys(tag: Tag) extends Table[PatternKeyRow](tag, "patternkeys") {
  def keyId = column[String]("keyid")     // key - the key name
  def patternId = column[String]("patternid")               // additional key - the composite orgid/patternid
  def key = column[String]("key")                   // the actual key content
  def lastUpdated = column[String]("lastupdated")
  def * = (keyId, patternId, key, lastUpdated).<>(PatternKeyRow.tupled, PatternKeyRow.unapply)
  def primKey = primaryKey("pk_ptk", (keyId, patternId))
  def pattern = foreignKey("pattern_fk", patternId, PatternsTQ)(_.pattern, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object PatternKeysTQ extends TableQuery(new PatternKeys(_)) {
  def getKeys(patternId: String): Query[PatternKeys, PatternKeyRow, Seq] = this.filter(_.patternId === patternId)
  def getKey(patternId: String, keyId: String): Query[PatternKeys, PatternKeyRow, Seq] = this.filter(r => {r.patternId === patternId && r.keyId === keyId} )
}

final case class PatternKey(key: String, lastUpdated: String)