package com.horizon.exchangeapi.tables

import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to microservices. */

case class MicroserviceRow(microservice: String, owner: String, label: String, description: String, specRef: String, version: String, arch: String, sharable: String, downloadUrl: String, matchHardware: String, userInput: String, workloads: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toMicroservice: Microservice = {
    val mh = if (matchHardware != "") read[Map[String,String]](matchHardware) else Map[String,String]()
    val input = if (userInput != "") read[List[Map[String,String]]](userInput) else List[Map[String,String]]()
    val wrk = if (workloads != "") read[List[Map[String,String]]](workloads) else List[Map[String,String]]()
    new Microservice(owner, label, description, specRef, version, arch, sharable, downloadUrl, mh, input, wrk, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- MicroservicesTQ.rows if m.microservice === microservice } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = MicroservicesTQ.rows += this
}

/** Mapping of the microservices db table to a scala class */
class Microservices(tag: Tag) extends Table[MicroserviceRow](tag, "mmicroservices") {
  def microservice = column[String]("microservice", O.PrimaryKey)
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def specRef = column[String]("specref")
  def version = column[String]("version")
  def arch = column[String]("arch")
  def sharable = column[String]("sharable")
  def downloadUrl = column[String]("downloadurl")
  def matchHardware = column[String]("matchhardware")
  def userInput = column[String]("userinput")
  def workloads = column[String]("workloads")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (microservice, owner, label, description, specRef, version, arch, sharable, downloadUrl, matchHardware, userInput, workloads, lastUpdated) <> (MicroserviceRow.tupled, MicroserviceRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the microservices table
object MicroservicesTQ {
  val rows = TableQuery[Microservices]

  def getMicroservice(microservice: String) = rows.filter(_.microservice === microservice)
  def getOwner(microservice: String) = rows.filter(_.microservice === microservice).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLabel(microservice: String) = rows.filter(_.microservice === microservice).map(_.label)
  def getDescription(microservice: String) = rows.filter(_.microservice === microservice).map(_.description)
  def getSpecRef(microservice: String) = rows.filter(_.microservice === microservice).map(_.specRef)
  def getVersion(microservice: String) = rows.filter(_.microservice === microservice).map(_.version)
  def getArch(microservice: String) = rows.filter(_.microservice === microservice).map(_.arch)
  def getSharable(microservice: String) = rows.filter(_.microservice === microservice).map(_.sharable)
  def getDownloadUrl(microservice: String) = rows.filter(_.microservice === microservice).map(_.downloadUrl)
  def getMatchHardware(microservice: String) = rows.filter(_.microservice === microservice).map(_.matchHardware)
  def getUserInput(microservice: String) = rows.filter(_.microservice === microservice).map(_.userInput)
  def getWorkloads(microservice: String) = rows.filter(_.microservice === microservice).map(_.workloads)
  def getLastUpdated(microservice: String) = rows.filter(_.microservice === microservice).map(_.lastUpdated)

  /** Returns a query for the specified microservice attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(microservice: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.microservice === microservice)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "specRef" => filter.map(_.specRef)
      case "version" => filter.map(_.version)
      case "arch" => filter.map(_.arch)
      case "sharable" => filter.map(_.sharable)
      case "downloadUrl" => filter.map(_.downloadUrl)
      case "matchHardware" => filter.map(_.matchHardware)
      case "userInput" => filter.map(_.userInput)
      case "workloads" => filter.map(_.workloads)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the microservice and the blockchains that reference it */
  def getDeleteActions(microservice: String): DBIO[_] = getMicroservice(microservice).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the microservice table minus the key - used as the data structure to return to the REST clients
class Microservice(var owner: String, var label: String, var description: String, var specRef: String, var version: String, var arch: String, var sharable: String, var downloadUrl: String, var matchHardware: Map[String,String], var userInput: List[Map[String,String]], var workloads: List[Map[String,String]], var lastUpdated: String) {
  // If we end up needing this, we might have to do deep copies of the variables that are actually structures
  //def copy = new Microservice(owner, label, description, specRef, version, arch, sharable, downloadUrl, matchHardware, userInput, workloads, lastUpdated)
}

