package com.horizon.exchangeapi.tables

import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to bctypes. */

case class BctypeRow(bctype: String, orgid: String, description: String, definedBy: String, details: String, lastUpdated: String) {
  // protected implicit val jsonFormats: Formats = DefaultFormats

  def toBctype: Bctype = {
    // val ci = if (containerInfo != "") read[Map[String,String]](containerInfo) else Map[String,String]()
    new Bctype(description, definedBy, details, lastUpdated)
  }

  def upsert: DBIO[_] = BctypesTQ.rows.insertOrUpdate(this)
}

/** Mapping of the bctypes db table to a scala class */
class Bctypes(tag: Tag) extends Table[BctypeRow](tag, "bctypes") {
  def bctype = column[String]("bctype", O.PrimaryKey)    // the content of this is orgid/workload
  def orgid = column[String]("orgid")
  def description = column[String]("description")
  def definedBy = column[String]("definedby")
  def details = column[String]("details")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (bctype, orgid, description, definedBy, details, lastUpdated) <> (BctypeRow.tupled, BctypeRow.unapply)
  def user = foreignKey("user_fk", definedBy, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the bctypes table
object BctypesTQ {
  val rows = TableQuery[Bctypes]

  def getAllBctypes(orgid: String) = rows.filter(_.orgid === orgid)
  def getBctype(bctype: String) = rows.filter(_.bctype === bctype)
  def getDescription(bctype: String) = rows.filter(_.bctype === bctype).map(_.description)
  def getOwner(bctype: String) = rows.filter(_.bctype === bctype).map(_.definedBy)
  def getNumOwned(owner: String) = rows.filter(_.definedBy === owner).length
  def getDetails(bctype: String) = rows.filter(_.bctype === bctype).map(_.details)
  def getLastUpdated(bctype: String) = rows.filter(_.bctype === bctype).map(_.lastUpdated)

  /** Returns a query for the specified bctype attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(bctype: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.bctype === bctype)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "description" => filter.map(_.description)
      case "definedBy" => filter.map(_.definedBy)
      case "details" => filter.map(_.details)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the bctype and the blockchains that reference it */
  def getDeleteActions(bctype: String): DBIO[_] = getBctype(bctype).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the bctype table minus the key - used as the data structure to return to the REST clients
class Bctype(var description: String, var definedBy: String, var details: String, var lastUpdated: String) {
  def copy = new Bctype(description, definedBy, details, lastUpdated)
}

/** One instance of a blockchain. From a rest api perspective, this is a sub-resource of bctype. */
case class BlockchainRow(name: String, bctype: String, orgid: String, description: String, definedBy: String, public: Boolean, details: String, lastUpdated: String) {
  def toBlockchain: Blockchain = {
    Blockchain(description, definedBy, public, details, lastUpdated)
  }

  def upsert: DBIO[_] = BlockchainsTQ.rows.insertOrUpdate(this)     //TODO: this might work now, the fix might be in 3.2 or 3.2.1 which came out in 7/2017. This currently does not work due to this bug: https://github.com/slick/slick/issues/966
  def update: DBIO[_] = BlockchainsTQ.getBlockchain(bctype,name).update(this)
  def insert: DBIO[_] = (BlockchainsTQ.rows += this)
}

class Blockchains(tag: Tag) extends Table[BlockchainRow](tag, "blockchains") {
  // def name = column[String]("name", O.PrimaryKey)     // name is not necessarily unique across all BC types, so need the type as a 2nd key
  def name = column[String]("name")     // name is not necessarily unique across all BC types, so need the type as a 2nd key
  def bctype = column[String]("bctype")
  def orgid = column[String]("orgid")
  def description = column[String]("description")
  def definedBy = column[String]("definedby")
  def public = column[Boolean]("public")
  def details = column[String]("details")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (name, bctype, orgid, description, definedBy, public, details, lastUpdated) <> (BlockchainRow.tupled, BlockchainRow.unapply)
  def primKey = primaryKey("pk_bc", (name, bctype))
  // def idx = index("idx_bc", (name, bctype), unique = true)
  def bctypekey = foreignKey("bctype_fk", bctype, BctypesTQ.rows)(_.bctype, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def user = foreignKey("user_fk", definedBy, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object BlockchainsTQ {
  val rows = TableQuery[Blockchains]

  def getAllBlockchains(orgid: String) = rows.filter(_.orgid === orgid)
  def getBlockchains(bctype: String) = rows.filter(_.bctype === bctype)
  def getBlockchain(bctype: String, name: String) = rows.filter( r => {r.bctype === bctype && r.name === name} )
  def getOwner(bctype: String, name: String) = rows.filter( r => {r.bctype === bctype && r.name === name} ).map(_.definedBy)

  /** The id is the name and bctype concatenated together */
  def getOwner2(id: String) = {
    //val (name, bctype) = id.split("""\|""", 2) match {
    val (bctype, name) = id.split("""\|""", 2) match {
      case Array(s) => (s, "")
      case Array(s1, s2) => (s1, s2)
      case _ => ("", "")
    }
    rows.filter( r => {r.bctype === bctype && r.name === name} ).map(_.definedBy)
  }

  def getNumOwned(owner: String) = rows.filter(_.definedBy === owner).length

  /** Returns a query for the specified blockchain attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(bctype: String, name: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter( r => {r.bctype === bctype && r.name === name} )
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "description" => filter.map(_.description)
      case "definedBy" => filter.map(_.definedBy)
      case "details" => filter.map(_.details)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }
}

case class Blockchain(description: String, definedBy: String, public: Boolean, details: String, lastUpdated: String)
