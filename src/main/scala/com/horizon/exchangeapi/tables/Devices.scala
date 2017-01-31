package com.horizon.exchangeapi.tables

import org.scalatra._
// import slick.driver.PostgresDriver.api._
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import com.horizon.exchangeapi._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection

/** Contains the object representations of the DB tables related to devices. */

case class DeviceRow(id: String, token: String, name: String, owner: String, msgEndPoint: String, softwareVersions: String, lastHeartbeat: String, publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toDevice(superUser: Boolean): Device = {
    val tok = if (superUser) token else StrConstants.hiddenPw
    val swv = if (softwareVersions != "") read[Map[String,String]](softwareVersions) else Map[String,String]()
    new Device(tok, name, owner, List[Microservice](), msgEndPoint, swv, lastHeartbeat, publicKey)
  }

  def putInHashMap(superUser: Boolean, devs: MutableHashMap[String,Device]): Unit = {
    devs.get(id) match {
      case Some(dev) => ; // do not need to add the device entry, because it is already there
      case None => devs.put(id, toDevice(superUser))
    }
  }

  def upsert: DBIO[_] = {
    // Note: this currently does not do the right thing for a blank token
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (owner == "root") DevicesTQ.rows.map(d => (d.id, d.token, d.name, d.msgEndPoint, d.softwareVersions, d.lastHeartbeat, d.publicKey)).insertOrUpdate((id, tok, name, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
    else DevicesTQ.rows.insertOrUpdate(DeviceRow(id, tok, name, owner, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
  }

  def update: DBIO[_] = {
    // Note: this currently does not do the right thing for a blank token
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (owner == "") (for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.token,d.name,d.msgEndPoint,d.softwareVersions,d.lastHeartbeat,d.publicKey)).update((id, tok, name, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
    else (for { d <- DevicesTQ.rows if d.id === id } yield d).update(DeviceRow(id, tok, name, owner, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
  }
}

/** Mapping of the devices db table to a scala class */
class Devices(tag: Tag) extends Table[DeviceRow](tag, "devices") {
  def id = column[String]("id", O.PrimaryKey)
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[String]("owner", O.Default("root"))  // root is the default because during upserts by root, we do not want root to take over the device if it already exists
  def msgEndPoint = column[String]("msgendpoint")
  def softwareVersions = column[String]("swversions")
  def lastHeartbeat = column[String]("lastheartbeat")
  def publicKey = column[String]("publickey")     // this is last because that is where alter table in upgradedb puts it
  // this describes what you get back when you return rows from a query
  def * = (id, token, name, owner, msgEndPoint, softwareVersions, lastHeartbeat, publicKey) <> (DeviceRow.tupled, DeviceRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the devices table
// object devices extends TableQuery(new Devices(_)) {
//   def listUserDevices(username: String) = this.filter(_.owner === username)
// }
object DevicesTQ {
  val rows = TableQuery[Devices]

  def getDevice(id: String) = rows.filter(_.id === id)
  def getToken(id: String) = rows.filter(_.id === id).map(_.token)
  def getOwner(id: String) = rows.filter(_.id === id).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLastHeartbeat(id: String) = rows.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String) = rows.filter(_.id === id).map(_.publicKey)

  /** Returns a query for the specified device attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "token" => filter.map(_.token)
      case "name" => filter.map(_.name)
      case "owner" => filter.map(_.owner)
      case "msgEndPoint" => filter.map(_.msgEndPoint)
      case "softwareVersions" => filter.map(_.softwareVersions)
      case "lastHeartbeat" => filter.map(_.lastHeartbeat)
      case "publicKey" => filter.map(_.publicKey)
      case _ => null
    }
  }

  /** Returns the actions to delete the device and any micros/props and agreements that reference it */
  def getDeleteActions(id: String): DBIO[_] = DBIO.seq(
      // now with all the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated rows
      // PropsTQ.rows.filter(_.propId like id+"|%").delete,       // delete the props that reference this device
      // MicroservicesTQ.rows.filter(_.deviceId === id).delete,       // delete the micros that reference this device
      // DeviceAgreementsTQ.getAgreements(id).delete,            // delete agreements that reference this device
      rows.filter(_.id === id).delete    // delete the device
    )

  /** Separate the join of the devices, microservices, properties, and swversions tables into their respective scala classes (collapsing duplicates) and return a hash containing it all */
  // def parseJoin(superUser: Boolean, list: Seq[(DeviceRow, Option[MicroserviceRow], Option[PropRow], Option[SoftwareVersionRow])]): Map[String,Device] = {
  def parseJoin(superUser: Boolean, list: Seq[(DeviceRow, Option[MicroserviceRow], Option[PropRow])] ): Map[String,Device] = {
    // Separate the partially duplicate join rows into maps that only keep unique values
    var devs = new MutableHashMap[String,Device]    // the key is device id
    var micros = new MutableHashMap[String,MutableHashMap[String,Microservice]]    // 1st key is device id, 2nd key is micro id
    var props = new MutableHashMap[String,MutableHashMap[String,Prop]]    // 1st key is micro id, 2nd key is prop id
    // var swVersions = new MutableHashMap[String,MutableHashMap[String,String]]    // 1st key is device id, 2nd key is sw name
    // for ((d, mOption, pOption, sOption) <- list) {
    for ((d, mOption, pOption) <- list) {
      d.putInHashMap(superUser, devs)
      if (!mOption.isEmpty) mOption.get.putInHashMap(micros)
      if (!pOption.isEmpty) pOption.get.putInHashMap(props)
      // if (!sOption.isEmpty) sOption.get.putInHashMap(swVersions)
    }

    // Now fill in the devs map, turning the maps we created above for micros, props, and swVersions into lists
    for ((devId, d) <- devs) {
      if (!micros.get(devId).isEmpty) {
        var microList = ListBuffer[Microservice]()
        for ((msId, m) <- micros.get(devId).get) {
          val propList = if (!props.get(msId).isEmpty) props.get(msId).get.values.toList else List[Prop]()
          microList += Microservice(m.url, m.numAgreements, m.policy, propList)
        }
        d.registeredMicroservices = microList.toList    // replace the empty micro list we put in there initially
      }
      // if (!swVersions.get(devId).isEmpty) d.softwareVersions = swVersions.get(devId).get.toMap
    }
    devs.toMap
  }
}

// This is the device table minus the key - used as the data structure to return to the REST clients
class Device(var token: String, var name: String, var owner: String, var registeredMicroservices: List[Microservice], var msgEndPoint: String, var softwareVersions: Map[String,String], var lastHeartbeat: String, var publicKey: String) {
  def copy = new Device(token, name, owner, registeredMicroservices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey)
}

case class DeviceAgreementRow(agId: String, deviceId: String, microservice: String, state: String, lastUpdated: String) {
  def toDeviceAgreement = DeviceAgreement(microservice, state, lastUpdated)

  def upsert: DBIO[_] = DeviceAgreementsTQ.rows.insertOrUpdate(this)
}

class DeviceAgreements(tag: Tag) extends Table[DeviceAgreementRow](tag, "devagreements") {
  def agId = column[String]("agid", O.PrimaryKey)     // ethereum agreeement ids are unique
  def deviceId = column[String]("deviceid")
  def microservice = column[String]("microservice")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def * = (agId, deviceId, microservice, state, lastUpdated) <> (DeviceAgreementRow.tupled, DeviceAgreementRow.unapply)
  def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object DeviceAgreementsTQ {
  val rows = TableQuery[DeviceAgreements]

  def getAgreements(deviceId: String) = rows.filter(_.deviceId === deviceId)
  def getAgreement(deviceId: String, agId: String) = rows.filter( r => {r.deviceId === deviceId && r.agId === agId} )
  def getNumOwned(deviceId: String) = rows.filter(_.deviceId === deviceId).length
  def getAgreementsWithState = rows.filter(_.state =!= "")
}

case class DeviceAgreement(microservice: String, state: String, lastUpdated: String)

/** Builds a hash of the current number of agreements for each device and microservice, so we can check them quickly */
class AgreementsHash(tempDbDevicesAgreements: MutableHashMap[String,MutableHashMap[String,DeviceAgreement]], dbDevicesAgreements: Seq[DeviceAgreementRow]) {
  // Alternate constructors, where the supply only 1 of the args
  def this(tempDbDevicesAgreements: MutableHashMap[String,MutableHashMap[String,DeviceAgreement]]) = this(tempDbDevicesAgreements, null)
  def this(dbDevicesAgreements: Seq[DeviceAgreementRow]) = this(null, dbDevicesAgreements)

  // The 1st level key of this hash is the device id, the 2nd level key is the microservice url, the leaf value is current number of agreements
  var agHash = new MutableHashMap[String,MutableHashMap[String,Int]]()

  if (tempDbDevicesAgreements != null) {
    for ((devid,d) <- tempDbDevicesAgreements) {
      for ((agid,ag) <- d; if ag.state != "" ) {
        // negotiation has at least started for this agreement, record it in the hash
        agHash.get(devid) match {
          case Some(devHash) => var numAgs = devHash.get(ag.microservice)      // device hash is there so find or create the microservice hash within it
            numAgs match {
              case Some(numAgs) => devHash.put(ag.microservice, numAgs+1)
              case None => devHash.put(ag.microservice, 1)
            }
          case None => agHash += ((devid, new MutableHashMap[String,Int]() += ((ag.microservice, 1)) ))   // this device is not in the hash yet, so create it and add the 1 microservice
        }
      }
    }
  } else if (dbDevicesAgreements != null) {
    for (a <- dbDevicesAgreements) {
      agHash.get(a.deviceId) match {
        case Some(devHash) => var numAgs = devHash.get(a.microservice)      // device hash is there so find or create the microservice hash within it
          numAgs match {
            case Some(numAgs) => devHash.put(a.microservice, numAgs+1)
            case None => devHash.put(a.microservice, 1)
          }
        case None => agHash += ((a.deviceId, new MutableHashMap[String,Int]() += ((a.microservice, 1)) ))   // this device is not in the hash yet, so create it and add the 1 microservice
      }
    }
  } else {}     //TODO: throw exception
}


/** The devmsgs table holds the msgs sent to devices by agbots */
case class DeviceMsgRow(msgId: Int, deviceId: String, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toDeviceMsg = DeviceMsg(msgId, agbotId, agbotPubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((DeviceMsgsTQ.rows returning DeviceMsgsTQ.rows.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = DeviceMsgsTQ.rows.insertOrUpdate(this)    // do not think we need this
}

class DeviceMsgs(tag: Tag) extends Table[DeviceMsgRow](tag, "devmsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def deviceId = column[String]("deviceid")       // msg recipient
  def agbotId = column[String]("agbotid")         // msg sender
  def agbotPubKey = column[String]("agbotpubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, deviceId, agbotId, agbotPubKey, message, timeSent, timeExpires) <> (DeviceMsgRow.tupled, DeviceMsgRow.unapply)
  def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object DeviceMsgsTQ {
  val rows = TableQuery[DeviceMsgs]

  def getMsgs(deviceId: String) = rows.filter(_.deviceId === deviceId)  // this is that devices msg mailbox
  def getMsg(deviceId: String, msgId: Int) = rows.filter( r => {r.deviceId === deviceId && r.msgId === msgId} )
  def getMsgsExpired = rows.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(deviceId: String) = rows.filter(_.deviceId === deviceId).length
}

case class DeviceMsg(msgId: Int, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String)

/*
case class SoftwareVersionRow(swId: Int, deviceId: String, name: String, version: String) {
  def toSoftwareVersion = version

  def putInHashMap(swVersions: MutableHashMap[String,MutableHashMap[String,String]]): Unit = {
    swVersions.get(deviceId) match {
      case Some(dev) => ; // do not need to add the entry, because it is already there
      case None => swVersions.put(deviceId, new MutableHashMap[String,String])
    }
    val sMap = swVersions.get(deviceId).get
    sMap.get(name) match {
      case Some(sw) => ; // do not need to add the entry, because it is already there
      case None => sMap.put(name, toSoftwareVersion)
    }
  }
}

class SoftwareVersions(tag: Tag) extends Table[SoftwareVersionRow](tag, "swversions") {
  def swId = column[Int]("swid", O.PrimaryKey, O.AutoInc)
  def deviceId = column[String]("devid")
  def name = column[String]("name")
  def version = column[String]("version")
  def * = (swId, deviceId, name, version) <> (SoftwareVersionRow.tupled, SoftwareVersionRow.unapply)
  def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id)
}

object SoftwareVersionsTQ {
  val rows = TableQuery[SoftwareVersions]
}
*/

case class MicroserviceRow(msId: String, deviceId: String, url: String, numAgreements: Int, policy: String) {
  def toMicroservice = Microservice(url, numAgreements, policy, List[Prop]())

  def putInHashMap(micros: MutableHashMap[String,MutableHashMap[String,Microservice]]): Unit = {
    micros.get(deviceId) match {
      case Some(dev) => ; // do not need to add the entry, because it is already there
      case None => micros.put(deviceId, new MutableHashMap[String,Microservice])
    }
    val mMap = micros.get(deviceId).get
    mMap.get(msId) match {
      case Some(ms) => ; // do not need to add the entry, because it is already there
      case None => mMap.put(msId, toMicroservice)
    }
  }

  def upsert: DBIO[_] = MicroservicesTQ.rows.insertOrUpdate(this)
  def update: DBIO[_] = MicroservicesTQ.rows.update(this)
}

class Microservices(tag: Tag) extends Table[MicroserviceRow](tag, "microservices") {
  def msId = column[String]("msid", O.PrimaryKey)     // we form this key as <deviceId>|<url>
  def deviceId = column[String]("deviceid")
  def url = column[String]("url")
  def numAgreements = column[Int]("numagreements")
  def policy = column[String]("policy")
  def * = (msId, deviceId, url, numAgreements, policy) <> (MicroserviceRow.tupled, MicroserviceRow.unapply)
  def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// object testmicros extends TableQuery(new TestMicros(_)) {}
object MicroservicesTQ {
  val rows = TableQuery[Microservices]
}

/** We define this trait because microservices in the DB and it the search criteria need the same methods, but have slightly different constructor args */
trait MicroserviceTrait {
  def url: String
  def properties: List[Prop]

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    for (p <- properties) {
      p.validate match {
        case Some(msg) => return Option[String](url+": "+msg)     // prepend the url so they know which microservice was bad
        case None => ;      // continue checking
      }
    }
    return None     // this means it is valid
  }

  /** Returns true if this micro (the search) matches that micro (an entry in the db)
   * Rules for comparison:
   * - if both parties do not have the same property names, it is as if wildcard was specified
   */
  def matches(that: Microservice): Boolean = {
    if (url != that.url) return false
    // go thru each of our props, finding and comparing the corresponding prop in that
    for (thatP <- that.properties) {
      properties.find(p => thatP.name == p.name) match {
        case None => ;        // if the device does not specify this property, that is equivalent to it specifying wildcard
        case Some(p) => if (!p.matches(thatP)) return false
      }
    }
    return true
  }
}

/** 1 microservice in the search criteria */
case class MicroserviceSearch(url: String, properties: List[Prop]) extends MicroserviceTrait

/** 1 microservice within a device in the DB */
case class Microservice(url: String, numAgreements: Int, policy: String, properties: List[Prop]) extends MicroserviceTrait {
  def toMicroserviceRow(deviceId: String) = MicroserviceRow(deviceId+"|"+url, deviceId, url, numAgreements, policy)
}

case class PropRow(propId: String, msId: String, name: String, value: String, propType: String, op: String) {
  def toProp = Prop(name, value, propType, op)

  def putInHashMap(props: MutableHashMap[String,MutableHashMap[String,Prop]]) {
    props.get(msId) match {
      case Some(ms) => ; // do not need to add the entry, because it is already there
      case None => props.put(msId, new MutableHashMap[String,Prop])
    }
    val pMap = props.get(msId).get
    pMap.get(propId) match {
      case Some(p) => ; // do not need to add the entry, because it is already there
      case None => pMap.put(propId, toProp)
    }
  }

  def upsert: DBIO[_] = PropsTQ.rows.insertOrUpdate(this)
  def update: DBIO[_] = PropsTQ.rows.update(this)
}

class Props(tag: Tag) extends Table[PropRow](tag, "properties") {
  def propId = column[String]("propid", O.PrimaryKey)     // we form this key as <msId>|<name>
  def msId = column[String]("msid")
  def name = column[String]("name")
  def value = column[String]("value")
  def propType = column[String]("proptype")
  def op = column[String]("op")
  def * = (propId, msId, name, value, propType, op) <> (PropRow.tupled, PropRow.unapply)
  // def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id)
  def ms = foreignKey("ms_fk", msId, MicroservicesTQ.rows)(_.msId, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object PropsTQ {
  val rows = TableQuery[Props]
}

/** 1 generic property that is used in the device search criteria */
case class Prop(name: String, value: String, propType: String, op: String) {
  def toPropRow(deviceId: String, msUrl: String) = PropRow(deviceId+"|"+msUrl+"|"+name, deviceId+"|"+msUrl, name, value, propType, op)

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    if (!PropType.contains(propType)) return Option[String]("invalid propType '"+propType+"' specified for "+name)
    if (!Op.contains(op)) return Option[String]("invalid op '"+op+"' specified for "+name)
    if (propType==PropType.BOOLEAN) {
      if (op!=Op.EQUAL) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.EQUAL+"' is supported for propType '"+PropType.BOOLEAN+"')")
      if (value.toLowerCase != "true" && value.toLowerCase != "false" && value != "*") return Option[String]("invalid boolean value '"+value+"' specified for "+name)
    }
    if ((propType==PropType.LIST || propType==PropType.STRING) && op!=Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.IN+"' is supported for propType '"+PropType.STRING+"' and '"+PropType.LIST+"')")
    if (propType==PropType.INT) {
      if (op==Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name)
      if (value != "*") {
        // ensure its a valid integer number
        try { val i = value.toInt }
        catch { case e: Exception => return Option[String]("invalid integer value '"+value+"' specified for "+name) }
      }
    }
    if (propType==PropType.VERSION) {
      if (!(op==Op.EQUAL || op==Op.IN)) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.EQUAL+"' or '"+Op.IN+"' is supported for propType '"+PropType.VERSION+"')")
      if (value != "*") {       // verify it is a valid version or range format
        if (!VersionRange(value).isValid) return Option[String]("invalid version value '"+value+"' specified for "+name)
      }
    }
    return None
  }

  /** Returns true if this property (the search) matches that property (an entry in the db) */
  def matches(that: Prop): Boolean = {
    if (name != that.name) return false     // comparison can only be done on the same name
    if (op != that.op) return false         // comparison only makes sense if they both have the same operator
    if (propType==PropType.WILDCARD || that.propType==PropType.WILDCARD) return true
    if (value=="*" || that.value=="*") return true
    (propType, that.propType) match {
      case (PropType.BOOLEAN, PropType.BOOLEAN) => op match {
        case Op.EQUAL => return (value == that.value)
        case _ => return false
      }
      // this will automatically transform a string into a list of strings
      case (PropType.LIST, PropType.LIST) | (PropType.STRING, PropType.LIST) | (PropType.LIST, PropType.STRING) | (PropType.STRING, PropType.STRING) => op match {
        case Op.IN => return ( value.split(",").intersect(that.value.split(",")).length > 0 )
        case _ => return false
      }
      case (PropType.INT, PropType.INT) => op match {
        case Op.EQUAL => return (value == that.value)
        case Op.GTEQUAL => try { return (that.value.toInt >= value.toInt) } catch { case e: Exception => return false }
        case Op.LTEQUAL => try { return (that.value.toInt <= value.toInt) } catch { case e: Exception => return false }
        case _ => return false
      }
      case (PropType.VERSION, PropType.VERSION) => op match {
        case Op.EQUAL => return (Version(value) == Version(that.value))
        case Op.IN => return (Version(that.value) in VersionRange(value))
        case _ => return false
      }
      case _ => return false
    }
  }
}

abstract class PropVar
case class PropList(args: String*) extends PropVar

// I do not think we can use actual enums in classes that get mapped to json and swagger
object PropType {
  val STRING = "string"
  val LIST = "list"
  val VERSION = "version"
  val BOOLEAN = "boolean"
  val INT = "int"
  val WILDCARD = "wildcard"       // means 1 side does not care what value the other side has
  val all = Set(STRING, LIST, VERSION, BOOLEAN, INT, WILDCARD)
  def contains(s: String): Boolean = all.contains(s)

}

/** The valid operators for properties */
object Op {
  val EQUAL = "="
  val GTEQUAL = ">="
  val LTEQUAL = "<="
  val IN = "in"
  val all = Set(EQUAL, GTEQUAL, LTEQUAL, IN )
  def contains(s: String): Boolean = all.contains(s)
}
