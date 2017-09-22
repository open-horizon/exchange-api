package com.horizon.exchangeapi.tables

import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to patterns. */

//case class PPriority(priority_value: Int, retries: Int, retry_durations: Int, verified_durations: Int)
//case class PUpgradePolicy(lifecycle: String, time: String)
case class PWorkloads(workloadUrl: String, workloadOrgid: String, workloadArch: String, workloadVersions: List[PWorkloadVersions], dataVerification: PDataVerification)
case class PWorkloadVersions(version: String, deployment_overrides: String, deployment_overrides_signature: String, priority: Map[String,Int], upgradePolicy: Map[String,String])
//case class PMetering(tokens: Int, per_time_unit: String, notification_interval: Int)
case class PDataVerification(enabled: Boolean, URL: String, user: String, password: String, interval: Int, check_rate: Int, metering: Map[String,Any])

//case class PatternRow(pattern: String, orgid: String, owner: String, label: String, description: String, public: Boolean, microservices: String, workloads: String, dataVerification: String, agreementProtocols: String, properties: String, counterPartyProperties: String, maxAgreements: Int, lastUpdated: String) {
case class PatternRow(pattern: String, orgid: String, owner: String, label: String, description: String, public: Boolean, workloads: String, agreementProtocols: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toPattern: Pattern = {
    //val micro = if (microservices != "") read[List[Map[String,String]]](microservices) else List[Map[String,String]]()
    val wrk = if (workloads != "") read[List[PWorkloads]](workloads) else List[PWorkloads]()
    //val dv = if (dataVerification != "") read[PDataVerification](dataVerification) else PDataVerification(false,"","","",0,0,Map[String,Any]())
    val agproto = if (agreementProtocols != "") read[List[Map[String,String]]](agreementProtocols) else List[Map[String,String]]()
    //val prop = if (properties != "") read[List[Map[String,Any]]](properties) else List[Map[String,Any]]()
    //val conprop = if (counterPartyProperties != "") read[Map[String,List[Map[String,Any]]]](counterPartyProperties) else Map[String,List[Map[String,Any]]]()
    //new Pattern(owner, label, description, public, micro, wrk, dv, agproto, prop, conprop, maxAgreements, lastUpdated)
    new Pattern(owner, label, description, public, wrk, agproto, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- PatternsTQ.rows if m.pattern === pattern } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = PatternsTQ.rows += this
}

/** Mapping of the patterns db table to a scala class */
class Patterns(tag: Tag) extends Table[PatternRow](tag, "patterns") {
  def pattern = column[String]("pattern", O.PrimaryKey)    // the content of this is orgid/pattern
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  //def microservices = column[String]("microservices")
  def workloads = column[String]("workloads")
  //def dataVerification = column[String]("dataVerification")
  def agreementProtocols = column[String]("agreementProtocols")
  //def properties = column[String]("properties")
  //def counterPartyProperties = column[String]("counterPartyProperties")
  //def maxAgreements = column[Int]("maxAgreements")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  //def * = (pattern, orgid, owner, label, description, public, microservices, workloads, dataVerification, agreementProtocols, properties, counterPartyProperties, maxAgreements, lastUpdated) <> (PatternRow.tupled, PatternRow.unapply)
  def * = (pattern, orgid, owner, label, description, public, workloads, agreementProtocols, lastUpdated) <> (PatternRow.tupled, PatternRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the patterns table
object PatternsTQ {
  val rows = TableQuery[Patterns]

  def getAllPatterns(orgid: String) = rows.filter(_.orgid === orgid)
  def getPattern(pattern: String) = rows.filter(_.pattern === pattern)
  def getOwner(pattern: String) = rows.filter(_.pattern === pattern).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLabel(pattern: String) = rows.filter(_.pattern === pattern).map(_.label)
  def getDescription(pattern: String) = rows.filter(_.pattern === pattern).map(_.description)
  def getPublic(pattern: String) = rows.filter(_.pattern === pattern).map(_.public)
  //def getMicroservices(pattern: String) = rows.filter(_.pattern === pattern).map(_.microservices)
  def getWorkloads(pattern: String) = rows.filter(_.pattern === pattern).map(_.workloads)
  //def getDataVerification(pattern: String) = rows.filter(_.pattern === pattern).map(_.dataVerification)
  def getAgreementProtocols(pattern: String) = rows.filter(_.pattern === pattern).map(_.agreementProtocols)
  //def getProperties(pattern: String) = rows.filter(_.pattern === pattern).map(_.properties)
  //def getCounterPartyProperties(pattern: String) = rows.filter(_.pattern === pattern).map(_.counterPartyProperties)
  //def getMaxAgreements(pattern: String) = rows.filter(_.pattern === pattern).map(_.maxAgreements)
  def getLastUpdated(pattern: String) = rows.filter(_.pattern === pattern).map(_.lastUpdated)

  /** Returns a query for the specified pattern attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(pattern: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.pattern === pattern)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "public" => filter.map(_.public)
      //case "microservices" => filter.map(_.microservices)
      case "workloads" => filter.map(_.workloads)
      //case "dataVerification" => filter.map(_.dataVerification)
      case "agreementProtocols" => filter.map(_.agreementProtocols)
      //case "properties" => filter.map(_.properties)
      //case "counterPartyProperties" => filter.map(_.counterPartyProperties)
      //case "maxAgreements" => filter.map(_.maxAgreements)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the pattern and the blockchains that reference it */
  def getDeleteActions(pattern: String): DBIO[_] = getPattern(pattern).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the pattern table minus the key - used as the data structure to return to the REST clients
//class Pattern(var owner: String, var label: String, var description: String, var public: Boolean, var microservices: List[Map[String,String]], var workloads: List[PWorkloads], var dataVerification: PDataVerification, var agreementProtocols: List[Map[String,String]], var properties: List[Map[String,Any]], var counterPartyProperties: Map[String,List[Map[String,Any]]], var maxAgreements: Int, var lastUpdated: String) {
class Pattern(var owner: String, var label: String, var description: String, var public: Boolean, var workloads: List[PWorkloads], var agreementProtocols: List[Map[String,String]], var lastUpdated: String) {
  def copy = new Pattern(owner, label, description, public, workloads, agreementProtocols, lastUpdated)
}

