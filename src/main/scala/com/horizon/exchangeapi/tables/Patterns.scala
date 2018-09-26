package com.horizon.exchangeapi.tables

import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.ListBuffer


/** Contains the object representations of the DB tables related to patterns. */

case class PWorkloads(workloadUrl: String, workloadOrgid: String, workloadArch: String, workloadVersions: List[PServiceVersions], dataVerification: Option[Map[String,Any]], nodeHealth: Option[Map[String,Int]])
case class PServices(serviceUrl: String, serviceOrgid: String, serviceArch: String, agreementLess: Option[Boolean], serviceVersions: List[PServiceVersions], dataVerification: Option[Map[String,Any]], nodeHealth: Option[Map[String,Int]])
//case class POldWorkloads(workloadUrl: String, workloadOrgid: String, workloadArch: String, workloadVersions: List[PWorkloadVersions], dataVerification: Map[String,Any])
case class PServiceVersions(version: String, deployment_overrides: String, deployment_overrides_signature: String, priority: Map[String,Int], upgradePolicy: Map[String,String])
case class PDataVerification(enabled: Boolean, URL: String, user: String, password: String, interval: Int, check_rate: Int, metering: Map[String,Any])

case class PatternRow(pattern: String, orgid: String, owner: String, label: String, description: String, public: Boolean, workloads: String, services: String, agreementProtocols: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toPattern: Pattern = {
    val wrk = if (workloads == "") List[PWorkloads]() else read[List[PWorkloads]](workloads)
    val svc = if (services == "") List[PServices]() else read[List[PServices]](services)
      /* Do not need this anymore because putting Option[] around the nodeHealth type makes the json reading and writing tolerant of it not being there
      {
        try { read[List[PWorkloads]](workloads) }
        catch { case _: MappingException => val oldWrk = read[List[POldWorkloads]](workloads)   // this pattern in the DB does not have the new nodeHealth field, so convert it
            val newList = new ListBuffer[PWorkloads]
            for (w <- oldWrk) { newList += PWorkloads(w.workloadUrl, w.workloadOrgid, w.workloadArch, w.workloadVersions, w.dataVerification, Some(Map())) }
            newList.toList
        }
      }
      */
    val agproto = if (agreementProtocols != "") read[List[Map[String,String]]](agreementProtocols) else List[Map[String,String]]()
    new Pattern(owner, label, description, public, wrk, svc, agproto, lastUpdated)
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
  def workloads = column[String]("workloads")
  def services = column[String]("services")
  def agreementProtocols = column[String]("agreementProtocols")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (pattern, orgid, owner, label, description, public, workloads, services, agreementProtocols, lastUpdated) <> (PatternRow.tupled, PatternRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the patterns table
object PatternsTQ {
  protected implicit val jsonFormats: Formats = DefaultFormats
  val rows = TableQuery[Patterns]

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds(services: List[PServices]): DBIO[Vector[Int]] = {
    // Currently, anax does not support a pattern with no services, so do not support that here
    val actions = ListBuffer[DBIO[Int]]()
    for (s <- services) {
      for (sv <- s.serviceVersions) {
        val svcId = ServicesTQ.formId(s.serviceOrgid, s.serviceUrl, sv.version, s.serviceArch)
        actions += ServicesTQ.getService(svcId).length.result
      }
    }
    //return DBIO.seq(actions: _*)      // convert the list of actions to a DBIO seq
    return DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence
  }

  // Build a list of db actions to verify that the referenced workloads exist
  def validateWorkloadIds(workloads: List[PWorkloads]): DBIO[Vector[Int]] = {
    // Currently, anax does not support a pattern with no workloads, so do not support that here
    val actions = ListBuffer[DBIO[Int]]()
    for (w <- workloads) {
      for (wv <- w.workloadVersions) {
        val workId = WorkloadsTQ.formId(w.workloadOrgid, w.workloadUrl, wv.version, w.workloadArch)
        //println("workId: "+workId)
        actions += WorkloadsTQ.getWorkload(workId).length.result
      }
    }
    //return DBIO.seq(actions: _*)      // convert the list of actions to a DBIO seq
    return DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence
  }

  def getAllPatterns(orgid: String) = rows.filter(_.orgid === orgid)
  def getPattern(pattern: String) = if (pattern.contains("%")) rows.filter(_.pattern like pattern) else rows.filter(_.pattern === pattern)
  def getOwner(pattern: String) = rows.filter(_.pattern === pattern).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLabel(pattern: String) = rows.filter(_.pattern === pattern).map(_.label)
  def getDescription(pattern: String) = rows.filter(_.pattern === pattern).map(_.description)
  def getPublic(pattern: String) = rows.filter(_.pattern === pattern).map(_.public)
  def getWorkloads(pattern: String) = rows.filter(_.pattern === pattern).map(_.workloads)
  def getServices(pattern: String) = rows.filter(_.pattern === pattern).map(_.services)
  def getServicesFromString(services: String) = if (services == "") List[PServices]() else read[List[PServices]](services)
  def getAgreementProtocols(pattern: String) = rows.filter(_.pattern === pattern).map(_.agreementProtocols)
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
      case "workloads" => filter.map(_.workloads)
      case "services" => filter.map(_.services)
      case "agreementProtocols" => filter.map(_.agreementProtocols)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the pattern and the blockchains that reference it */
  def getDeleteActions(pattern: String): DBIO[_] = getPattern(pattern).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the pattern table minus the key - used as the data structure to return to the REST clients
class Pattern(var owner: String, var label: String, var description: String, var public: Boolean, var workloads: List[PWorkloads], var services: List[PServices], var agreementProtocols: List[Map[String,String]], var lastUpdated: String) {
  def copy = new Pattern(owner, label, description, public, workloads, services, agreementProtocols, lastUpdated)
}


// Key is a sub-resource of pattern
case class PatternKeyRow(keyId: String, patternId: String, key: String, lastUpdated: String) {
  def toPatternKey = PatternKey(key, lastUpdated)

  def upsert: DBIO[_] = PatternKeysTQ.rows.insertOrUpdate(this)
}

class PatternKeys(tag: Tag) extends Table[PatternKeyRow](tag, "patternkeys") {
  def keyId = column[String]("keyid")     // key - the key name
  def patternId = column[String]("patternid")               // additional key - the composite orgid/patternid
  def key = column[String]("key")                   // the actual key content
  def lastUpdated = column[String]("lastupdated")
  def * = (keyId, patternId, key, lastUpdated) <> (PatternKeyRow.tupled, PatternKeyRow.unapply)
  def primKey = primaryKey("pk_ptk", (keyId, patternId))
  def pattern = foreignKey("pattern_fk", patternId, PatternsTQ.rows)(_.pattern, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object PatternKeysTQ {
  val rows = TableQuery[PatternKeys]

  def getKeys(patternId: String) = rows.filter(_.patternId === patternId)
  def getKey(patternId: String, keyId: String) = rows.filter( r => {r.patternId === patternId && r.keyId === keyId} )
}

case class PatternKey(key: String, lastUpdated: String)
