package com.horizon.exchangeapi.tables

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.slf4j._
import org.scalatra.json._
import com.horizon.exchangeapi._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection

/** Contains the object representations of the DB tables related to bctypes. */

case class BctypeRow(bctype: String, description: String, definedBy: String, containerInfo: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toBctype: Bctype = {
    val ci = if (containerInfo != "") read[Map[String,String]](containerInfo) else Map[String,String]()
    new Bctype(description, definedBy, ci, lastUpdated)
  }

  def upsert: DBIO[_] = BctypesTQ.rows.insertOrUpdate(this)
}

/** Mapping of the bctypes db table to a scala class */
class Bctypes(tag: Tag) extends Table[BctypeRow](tag, "bctypes") {
  def bctype = column[String]("bctype", O.PrimaryKey)
  def description = column[String]("description")
  def definedBy = column[String]("definedby")
  def containerInfo = column[String]("containerinfo")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (bctype, description, definedBy, containerInfo, lastUpdated) <> (BctypeRow.tupled, BctypeRow.unapply)
  def user = foreignKey("user_fk", definedBy, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the bctypes table
object BctypesTQ {
  val rows = TableQuery[Bctypes]

  def getBctype(bctype: String) = rows.filter(_.bctype === bctype)
  def getDescription(bctype: String) = rows.filter(_.bctype === bctype).map(_.description)
  def getOwner(bctype: String) = rows.filter(_.bctype === bctype).map(_.definedBy)
  def getNumOwned(owner: String) = rows.filter(_.definedBy === owner).length
  def getContainerInfo(bctype: String) = rows.filter(_.bctype === bctype).map(_.containerInfo)
  def getLastUpdated(bctype: String) = rows.filter(_.bctype === bctype).map(_.lastUpdated)

  /** Returns a query for the specified bctype attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(bctype: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.bctype === bctype)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "description" => filter.map(_.description)
      case "definedBy" => filter.map(_.definedBy)
      case "containerInfo" => filter.map(_.containerInfo)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the bctype and the blockchains that reference it */
  def getDeleteActions(bctype: String): DBIO[_] = getBctype(bctype).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the bctype table minus the key - used as the data structure to return to the REST clients
class Bctype(var description: String, var definedBy: String, var containerInfo: Map[String,String], var lastUpdated: String) {
  def copy = new Bctype(description, definedBy, containerInfo, lastUpdated)
}

/** One instance of a blockchain. From a rest api perspective, this is a sub-resource of bctype. */
case class BlockchainRow(name: String, bctype: String, description: String, definedBy: String, bootNodes: String, genesis: String, networkId: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toBlockchain: Blockchain = {
    val bn = if (bootNodes != "") read[List[String]](bootNodes) else List[String]()
    val gen = if (genesis != "") read[List[String]](genesis) else List[String]()
    val netid = if (networkId != "") read[List[String]](networkId) else List[String]()
    Blockchain(description, definedBy, bn, gen, netid, lastUpdated)
  }

  def upsert: DBIO[_] = BlockchainsTQ.rows.insertOrUpdate(this)     //todo: this currently does not work due to this bug: https://github.com/slick/slick/issues/966
  def update: DBIO[_] = BlockchainsTQ.getBlockchain(bctype,name).update(this)
  def insert: DBIO[_] = (BlockchainsTQ.rows += this)
}

class Blockchains(tag: Tag) extends Table[BlockchainRow](tag, "blockchains") {
  // def name = column[String]("name", O.PrimaryKey)     // name is not necessarily unique across all BC types, so need the type as a 2nd key
  def name = column[String]("name")     // name is not necessarily unique across all BC types, so need the type as a 2nd key
  def bctype = column[String]("bctype")
  def description = column[String]("description")
  def definedBy = column[String]("definedby")
  def bootNodes = column[String]("bootnodes")   // for now we are serializing the json and storing in a string, instead of using another table and doing joins
  def genesis = column[String]("genesis")
  def networkId = column[String]("networkid")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (name, bctype, description, definedBy, bootNodes, genesis, networkId, lastUpdated) <> (BlockchainRow.tupled, BlockchainRow.unapply)
  def primKey = primaryKey("pk_bc", (name, bctype))
  // def idx = index("idx_bc", (name, bctype), unique = true)
  def bctypekey = foreignKey("bctype_fk", bctype, BctypesTQ.rows)(_.bctype, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def user = foreignKey("user_fk", definedBy, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object BlockchainsTQ {
  val rows = TableQuery[Blockchains]

  def getBlockchains(bctype: String) = rows.filter(_.bctype === bctype)
  def getBlockchain(bctype: String, name: String) = rows.filter( r => {r.bctype === bctype && r.name === name} )
  def getOwner(bctype: String, name: String) = rows.filter( r => {r.bctype === bctype && r.name === name} ).map(_.definedBy)

  /** The id is the name and bctype concatenated together */
  def getOwner2(id: String) = {
    val (name, bctype) = id.split("""\|""", 2) match {
      case Array(s) => (s, "")
      case Array(s1, s2) => (s1, s2)
      case _ => ("", "")
    }
    // println("getOwner2: name: "+name+", bctype: "+bctype)
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
      case "bootNodes" => filter.map(_.bootNodes)
      case "genesis" => filter.map(_.genesis)
      case "networkId" => filter.map(_.networkId)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }
}

case class Blockchain(description: String, definedBy: String, bootNodes: List[String], genesis: List[String], networkId: List[String], lastUpdated: String)
