package com.horizon.exchangeapi

import org.scalatra._
// import slick.driver.PostgresDriver.api._
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.slf4j._
import java.io._
import scala.util._
import scala.collection.mutable.{ListBuffer, Set => MutableSet, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
// import scala.collection.immutable._
import com.horizon.exchangeapi.tables._

/** The umbrella class for the DB tables. The specific table classes are in the tables subdir. */
object ExchangeApiTables {

  val create = (UsersTQ.rows.schema ++ DevicesTQ.rows.schema ++ MicroservicesTQ.rows.schema ++ PropsTQ.rows.schema ++ DeviceAgreementsTQ.rows.schema ++ AgbotsTQ.rows.schema ++ AgbotAgreementsTQ.rows.schema ++ DeviceMsgsTQ.rows.schema ++ AgbotMsgsTQ.rows.schema).create

  // Alter the schema of existing tables
  val alterTables = DBIO.seq(sqlu"alter table devices add column if not exists publickey character varying not null default ''", sqlu"alter table agbots add column if not exists publickey character varying not null default ''")

  // Used to create just the new tables in this version, so we do not have to disrupt the existing tables
  val createNewTables = (DeviceMsgsTQ.rows.schema ++ AgbotMsgsTQ.rows.schema).create

  // val delete = (AgbotAgreementsTQ.rows.schema ++ AgbotsTQ.rows.schema ++ DeviceAgreementsTQ.rows.schema ++ PropsTQ.rows.schema ++ MicroservicesTQ.rows.schema ++ DevicesTQ.rows.schema ++ UsersTQ.rows.schema).drop
  // Note: doing this because a foreign key constraint not existing was causing slick's drops to fail. As long as we are not removing contraints (only adding), we should be ok with the drops below?
  val delete = DBIO.seq(sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table devices", sqlu"drop table users")

  // Remove the alters of existing tables
  val unAlterTables = DBIO.seq(sqlu"alter table devices drop column if exists publickey", sqlu"alter table agbots drop column if exists publickey")

  // Used to delete just the new tables in this version (so we can recreate), so we do not have to disrupt the existing tables
  val deleteNewTables = DBIO.seq(sqlu"drop table devmsgs", sqlu"drop table agbotmsgs")

  // Populate the tables with a few rows. This is rarely used.
  val setup = DBIO.seq(
    UsersTQ.rows += UserRow("bp", Password.hash("mypw"), "bruceandml@gmail.com", ApiTime.nowUTC),
    DevicesTQ.rows += DeviceRow("1", Password.hash("abc123"), "rpi1", "bp", "whisper-1", """{"horizon":"1.2.3"}""", ApiTime.nowUTC, "ABC"),
      // SoftwareVersionsTQ.rows += SoftwareVersionRow(0, "1", "kernel", "3.13.0-79-generic"),
      MicroservicesTQ.rows += MicroserviceRow("1|http://bluehorizon.network/documentation/sdr-device-api", "1", "http://bluehorizon.network/documentation/sdr-device-api", 1, "{dev1-sdr-policy}"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/sdr-device-api|arch", "1|http://bluehorizon.network/documentation/sdr-device-api", "arch", "arm", "string", "in"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/sdr-device-api|memory", "1|http://bluehorizon.network/documentation/sdr-device-api", "memory", "300", "int", ">="),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/sdr-device-api|version", "1|http://bluehorizon.network/documentation/sdr-device-api", "version", "1.0.0", "version", "in"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/sdr-device-api|agreementProtocols", "1|http://bluehorizon.network/documentation/sdr-device-api", "agreementProtocols", "ExchangeManualTest", "list", "in"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/sdr-device-api|dataVerification", "1|http://bluehorizon.network/documentation/sdr-device-api", "dataVerification", "true", "boolean", "="),
      MicroservicesTQ.rows += MicroserviceRow("1|http://bluehorizon.network/documentation/netspeed-device-api", "1", "http://bluehorizon.network/documentation/netspeed-device-api", 1, "{dev1-netspeed-policy}"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/netspeed-device-api|arch", "1|http://bluehorizon.network/documentation/netspeed-device-api", "arch", "arm", "string", "in"),
        PropsTQ.rows += PropRow("1|http://bluehorizon.network/documentation/netspeed-device-api|memory", "1|http://bluehorizon.network/documentation/netspeed-device-api", "version", "1.0.0", "version", "in"),

    DevicesTQ.rows += DeviceRow("d2", Password.hash("abc"), "rpi2", "bp", "whisper-d2", "{}", ApiTime.nowUTC, "ABC2"),
      MicroservicesTQ.rows += MicroserviceRow("d2|http:///netspeed", "d2", "http:///netspeed", 1, "{dev2-netspeed-policy}"),
        PropsTQ.rows += PropRow("d2|http:///netspeed|arch", "d2|http:///netspeed", "arch", "arm", "string", "in"),
        PropsTQ.rows += PropRow("d2|http:///netspeed|agreementProtocols", "d2|http:///netspeed", "agreementProtocols", "ExchangeManualTest", "list", "in")
  )

  /** Returns a db action that queries each table and dumps it to a file in json format */
  def dump(dumpDir: String, dumpSuffix: String)(implicit logger: Logger): DBIO[_] = {
    // This is a single db action that strings together via flatMap all the table queries and writing each result to a file
    return UsersTQ.rows.result.flatMap({ xs =>
      val filename = dumpDir+"/users"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[UserRow](filename).dump(xs)
      DevicesTQ.rows.result     // the next query, processed by the following flatMap
    }).flatMap({ xs =>
      val filename = dumpDir+"/devices"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[DeviceRow](filename).dump(xs)
      MicroservicesTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/microservices"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[MicroserviceRow](filename).dump(xs)
      PropsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/properties"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[PropRow](filename).dump(xs)
      DeviceAgreementsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/devagreements"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[DeviceAgreementRow](filename).dump(xs)
      AgbotsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/agbots"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[AgbotRow](filename).dump(xs)
      AgbotAgreementsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/agbotagreements"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[AgbotAgreementRow](filename).dump(xs)
      DeviceMsgsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/devmsgs"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[DeviceMsgRow](filename).dump(xs)
      AgbotMsgsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/agbotmsgs"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[AgbotMsgRow](filename).dump(xs)
      AgbotAgreementsTQ.rows.result     // we do not need this redundant query, but flatMap has to return an action
    })


    /* previous attemp, but left here as an example of how to wait on multiple futures...
    val fUsers: Future[Try[String]] = db.run(UsersTQ.rows.result.asTry).map({ xs =>
      val filename = dir+"/users"+suffix
      xs match {
        case Success(v) => new TableIo[UserRow](filename).dump(v)
          logger.info("dumped "+v.size+" rows to "+filename)
          Success("dumped "+v.size+" rows to "+filename)
        case Failure(t) => logger.error("error dumping rows to "+filename)
          Failure(t)
      }
    })
    val fDevices: Future[Try[String]] = db.run(DevicesTQ.rows.result.asTry).map({ xs =>
      val filename = dir+"/devices"+suffix
      xs match {
        case Success(v) => new TableIo[DeviceRow](filename).dump(v)
          logger.info("dumped "+v.size+" rows to "+filename)
          Success("dumped "+v.size+" rows to "+filename)
        case Failure(t) => logger.error("error dumping rows to "+filename)
          Failure(t)
      }
    })
    val aggFut = for {
      f1Result <- fUsers
      f2Result <- fDevices
    } yield Vector(f1Result, f2Result)
    aggFut
    */
  }

  /** Returns a list of db actions for loading the contents of the dumped json files into the tables */
  def load(dumpDir: String, dumpSuffix: String)(implicit logger: Logger): List[DBIO[_]] = {
    val actions = ListBuffer[DBIO[_]]()

    // Load the table file and put it on the actions list. Repeating this for each table here, because read[]() needs an explicit type
    // Note: this intentionally does not catch the json parsing exceptions, so they will get thrown to the caller and they can handle them
    val users = new TableIo[UserRow](dumpDir+"/users"+dumpSuffix).load
    if (users.size > 0) actions += (UsersTQ.rows ++= users)

    val devices = new TableIo[DeviceRow](dumpDir+"/devices"+dumpSuffix).load
    if (devices.size > 0) actions += (DevicesTQ.rows ++= devices)

    val microservices = new TableIo[MicroserviceRow](dumpDir+"/microservices"+dumpSuffix).load
    if (microservices.size > 0) actions += (MicroservicesTQ.rows ++= microservices)

    val properties = new TableIo[PropRow](dumpDir+"/properties"+dumpSuffix).load
    if (properties.size > 0) actions += (PropsTQ.rows ++= properties)

    val devagreements = new TableIo[DeviceAgreementRow](dumpDir+"/devagreements"+dumpSuffix).load
    if (devagreements.size > 0) actions += (DeviceAgreementsTQ.rows ++= devagreements)

    val agbots = new TableIo[AgbotRow](dumpDir+"/agbots"+dumpSuffix).load
    if (agbots.size > 0) actions += (AgbotsTQ.rows ++= agbots)

    val agbotagreements = new TableIo[AgbotAgreementRow](dumpDir+"/agbotagreements"+dumpSuffix).load
    if (agbotagreements.size > 0) actions += (AgbotAgreementsTQ.rows ++= agbotagreements)

    val devicemsgs = new TableIo[DeviceMsgRow](dumpDir+"/devmsgs"+dumpSuffix).load
    if (devicemsgs.size > 0) actions += (DeviceMsgsTQ.rows ++= devicemsgs)

    val agbotmsgs = new TableIo[AgbotMsgRow](dumpDir+"/agbotmsgs"+dumpSuffix).load
    if (agbotmsgs.size > 0) actions += (AgbotMsgsTQ.rows ++= agbotmsgs)

    return actions.toList
  }
}

class TableIo[T](val filename: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Writes a table to a file in json format */
  def dump(rows: Seq[T]) = {
    // read[Map[String,String]](softwareVersions)
    val file = new File(filename)
    file.getParentFile().mkdirs()
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(write(rows))     // the inside write() converts the scala data structure to a json blob
    bw.close()
  }

  /** Returns a sequence of the rows of the table read from a json file */
  def load(implicit logger: Logger, m: Manifest[Seq[T]]): Seq[T] = {
    val content = scala.io.Source.fromFile(filename).mkString
    //todo: compiler complains about this saying: No Manifest available for Seq[T]
    read[Seq[T]](content)
    // try { read[Seq[T]](content) }
    // catch { case e: Exception => logger.error("Error parsing "+filename+" as json: "+e); Seq[T]() }    // the specific exception is MappingException
  }
}
