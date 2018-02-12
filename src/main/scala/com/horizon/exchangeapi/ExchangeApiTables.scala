package com.horizon.exchangeapi

import java.io._

import org.json4s._
import org.json4s.jackson.Serialization.{read, write}
import org.slf4j._
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import com.horizon.exchangeapi.tables._

/** The umbrella class for the DB tables. The specific table classes are in the tables subdir. */
object ExchangeApiTables {

  // Create all of the current version's tables - used in /admin/initdb
  val create = (
    SchemaTQ.rows.schema ++ OrgsTQ.rows.schema ++ UsersTQ.rows.schema
      ++ NodesTQ.rows.schema ++ RegMicroservicesTQ.rows.schema ++ PropsTQ.rows.schema ++ NodeAgreementsTQ.rows.schema ++ NodeStatusTQ.rows.schema
      ++ AgbotsTQ.rows.schema ++ AgbotAgreementsTQ.rows.schema ++ AgbotPatternsTQ.rows.schema
      ++ NodeMsgsTQ.rows.schema ++ AgbotMsgsTQ.rows.schema
      ++ BctypesTQ.rows.schema ++ BlockchainsTQ.rows.schema ++ ServicesTQ.rows.schema ++ ServiceKeysTQ.rows.schema ++ MicroservicesTQ.rows.schema ++ MicroserviceKeysTQ.rows.schema ++ WorkloadsTQ.rows.schema ++ WorkloadKeysTQ.rows.schema ++ PatternsTQ.rows.schema
    ).create

  // Alter the schema of existing tables - used to be used in /admin/upgradedb
  // Note: the compose/bluemix version of postgresql does not support the 'if not exists' option
  // val alterTables = DBIO.seq(sqlu"alter table nodes add column publickey character varying not null default ''", sqlu"alter table agbots add column publickey character varying not null default ''")
  // val alterTables = DBIO.seq(sqlu"alter table nodes drop column publickey", sqlu"alter table agbots drop column publickey")

  // Used to create just the new tables in this version, so we do not have to disrupt the existing tables - used to be used in /admin/initnewtables and /admin/upgradedb
  //val createNewTables = (MicroservicesTQ.rows.schema ++ WorkloadsTQ.rows.schema).create

  // Delete all of the current tables - the tables that are depended on need to be last in this list - used in /admin/dropdb
  // Note: doing this with raw sql stmts because a foreign key constraint not existing was causing slick's drops to fail. As long as we are not removing contraints (only adding), we should be ok with the drops below?
  //val delete = DBIO.seq(sqlu"drop table orgs", sqlu"drop table workloads", sqlu"drop table mmicroservices", sqlu"drop table blockchains", sqlu"drop table bctypes", sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table nodes", sqlu"drop table users")
  val delete = DBIO.seq(
    sqlu"drop table if exists patterns", sqlu"drop table if exists servicekeys", sqlu"drop table if exists services", sqlu"drop table if exists workloadkeys", sqlu"drop table if exists workloads", sqlu"drop table if exists blockchains", sqlu"drop table if exists bctypes",  // no table depends on these
    sqlu"drop table if exists mmicroservices",       // from older schema
    sqlu"drop table if exists devmsgs",   // from older schema
    sqlu"drop table if exists nodemsgs", sqlu"drop table if exists agbotmsgs",     // these depend on both nodes and agbots
    sqlu"drop table if exists agbotpatterns", sqlu"drop table if exists agbotagreements", sqlu"drop table if exists agbots",
    sqlu"drop table if exists devagreements",   // from older schema
    sqlu"drop table if exists nodeagreements", sqlu"drop table if exists nodestatus",
    sqlu"drop table if exists properties",
    sqlu"drop table if exists microservicekeys", sqlu"drop table if exists microservices", sqlu"drop table if exists devmicros", sqlu"drop table if exists devices",   // from older schema
    sqlu"drop table if exists nodemicros", sqlu"drop table if exists nodes",
    sqlu"drop table if exists users", sqlu"drop table if exists orgs", sqlu"drop table if exists schema"
  )

  // Delete the previous version's tables - used to be used by /admin/migratedb
  //val deletePrevious = DBIO.seq(sqlu"drop table workloads", sqlu"drop table mmicroservices", sqlu"drop table blockchains", sqlu"drop table bctypes", sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table nodes", sqlu"drop table users")

  // Remove the alters of existing tables - used to be used by /admin/unupgradedb
  // val unAlterTables = DBIO.seq(sqlu"alter table nodes drop column publickey", sqlu"alter table agbots drop column publickey")
  // val unAlterTables = DBIO.seq(sqlu"alter table nodes add column publickey character varying not null default ''", sqlu"alter table agbots add column publickey character varying not null default ''")

  // Used to delete just the new tables in this version (so we can recreate) - used by /admin/unupgradedb
  val deleteNewTables = DBIO.seq(sqlu"drop table if exists workloadkeys")

  /** Returns a db action that queries each table and dumps it to a file in json format - used in /admin/dumptables and /admin/migratedb */
  def dump(dumpDir: String, dumpSuffix: String)(implicit logger: Logger): DBIO[_] = {
    // This is a single db action that strings together via flatMap all the table queries and writing each result to a file
    return UsersTQ.rows.result.flatMap({ xs =>
      val filename = dumpDir+"/users"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[UserRow](filename).dump(xs)
      NodesTQ.rows.result     // the next query, processed by the following flatMap
    }).flatMap({ xs =>
      val filename = dumpDir+"/nodes"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[NodeRow](filename).dump(xs)
      RegMicroservicesTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/nodemicros"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[RegMicroserviceRow](filename).dump(xs)
      PropsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/properties"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[PropRow](filename).dump(xs)
      NodeAgreementsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/nodeagreements"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[NodeAgreementRow](filename).dump(xs)
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
      NodeMsgsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/nodemsgs"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[NodeMsgRow](filename).dump(xs)
      AgbotMsgsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/agbotmsgs"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[AgbotMsgRow](filename).dump(xs)
      BctypesTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/bctypes"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[BctypeRow](filename).dump(xs)
      BlockchainsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/blockchains"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[BlockchainRow](filename).dump(xs)
      ServicesTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/services"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[ServiceRow](filename).dump(xs)
      ServiceKeysTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/servicekeys"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[ServiceKeyRow](filename).dump(xs)
      MicroservicesTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/microservices"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[MicroserviceRow](filename).dump(xs)
      MicroserviceKeysTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/microservicekeys"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[MicroserviceKeyRow](filename).dump(xs)
      WorkloadsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/workloads"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[WorkloadRow](filename).dump(xs)
      WorkloadKeysTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/workloadkeys"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[WorkloadKeyRow](filename).dump(xs)
      PatternsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/patterns"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[PatternRow](filename).dump(xs)
      OrgsTQ.rows.result
    }).flatMap({ xs =>
      val filename = dumpDir+"/orgs"+dumpSuffix
      logger.info("dumping "+xs.size+" rows to "+filename)
      new TableIo[OrgRow](filename).dump(xs)
      OrgsTQ.rows.result     // we do not need this redundant query, but flatMap has to return an action
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
    val fNodes: Future[Try[String]] = db.run(NodesTQ.rows.result.asTry).map({ xs =>
      val filename = dir+"/nodes"+suffix
      xs match {
        case Success(v) => new TableIo[NodeRow](filename).dump(v)
          logger.info("dumped "+v.size+" rows to "+filename)
          Success("dumped "+v.size+" rows to "+filename)
        case Failure(t) => logger.error("error dumping rows to "+filename)
          Failure(t)
      }
    })
    val aggFut = for {
      f1Result <- fUsers
      f2Result <- fNodes
    } yield Vector(f1Result, f2Result)
    aggFut
    */
  }

  /** Returns a list of db actions for loading the contents of the dumped json files into the tables- used in /admin/loadtables and /admin/migratedb */
  def load(dumpDir: String, dumpSuffix: String)(implicit logger: Logger): List[DBIO[_]] = {
    val actions = ListBuffer[DBIO[_]]()

    // Load the table file and put it on the actions list. Repeating this for each table here, because read[]() needs an explicit type
    // Note: this intentionally does not catch the json parsing exceptions, so they will get thrown to the caller and they can handle them
    val users = new TableIo[UserRow](dumpDir+"/users"+dumpSuffix).load
    if (users.nonEmpty) actions += (UsersTQ.rows ++= users)

    val nodes = new TableIo[NodeRow](dumpDir+"/nodes"+dumpSuffix).load
    if (nodes.nonEmpty) actions += (NodesTQ.rows ++= nodes)

    val nodemicros = new TableIo[RegMicroserviceRow](dumpDir+"/nodemicros"+dumpSuffix).load
    if (nodemicros.nonEmpty) actions += (RegMicroservicesTQ.rows ++= nodemicros)

    val properties = new TableIo[PropRow](dumpDir+"/properties"+dumpSuffix).load
    if (properties.nonEmpty) actions += (PropsTQ.rows ++= properties)

    val nodeagreements = new TableIo[NodeAgreementRow](dumpDir+"/nodeagreements"+dumpSuffix).load
    if (nodeagreements.nonEmpty) actions += (NodeAgreementsTQ.rows ++= nodeagreements)

    val agbots = new TableIo[AgbotRow](dumpDir+"/agbots"+dumpSuffix).load
    if (agbots.nonEmpty) actions += (AgbotsTQ.rows ++= agbots)

    val agbotagreements = new TableIo[AgbotAgreementRow](dumpDir+"/agbotagreements"+dumpSuffix).load
    if (agbotagreements.nonEmpty) actions += (AgbotAgreementsTQ.rows ++= agbotagreements)

    val nodemsgs = new TableIo[NodeMsgRow](dumpDir+"/nodemsgs"+dumpSuffix).load
    if (nodemsgs.nonEmpty) actions += (NodeMsgsTQ.rows ++= nodemsgs)

    val agbotmsgs = new TableIo[AgbotMsgRow](dumpDir+"/agbotmsgs"+dumpSuffix).load
    if (agbotmsgs.nonEmpty) actions += (AgbotMsgsTQ.rows ++= agbotmsgs)

    val bctypes = new TableIo[BctypeRow](dumpDir+"/bctypes"+dumpSuffix).load
    if (bctypes.nonEmpty) actions += (BctypesTQ.rows ++= bctypes)

    val blockchains = new TableIo[BlockchainRow](dumpDir+"/blockchains"+dumpSuffix).load
    if (blockchains.nonEmpty) actions += (BlockchainsTQ.rows ++= blockchains)

    val services = new TableIo[ServiceRow](dumpDir+"/services"+dumpSuffix).load
    if (services.nonEmpty) actions += (ServicesTQ.rows ++= services)

    val servicekeys = new TableIo[ServiceKeyRow](dumpDir+"/servicekeys"+dumpSuffix).load
    if (servicekeys.nonEmpty) actions += (ServiceKeysTQ.rows ++= servicekeys)

    val microservices = new TableIo[MicroserviceRow](dumpDir+"/microservices"+dumpSuffix).load
    if (microservices.nonEmpty) actions += (MicroservicesTQ.rows ++= microservices)

    val microservicekeys = new TableIo[MicroserviceKeyRow](dumpDir+"/microservicekeys"+dumpSuffix).load
    if (microservicekeys.nonEmpty) actions += (MicroserviceKeysTQ.rows ++= microservicekeys)

    val workloads = new TableIo[WorkloadRow](dumpDir+"/workloads"+dumpSuffix).load
    if (workloads.nonEmpty) actions += (WorkloadsTQ.rows ++= workloads)

    val workloadkeys = new TableIo[WorkloadKeyRow](dumpDir+"/workloadkeys"+dumpSuffix).load
    if (workloadkeys.nonEmpty) actions += (WorkloadKeysTQ.rows ++= workloadkeys)

    val patterns = new TableIo[PatternRow](dumpDir+"/patterns"+dumpSuffix).load
    if (patterns.nonEmpty) actions += (PatternsTQ.rows ++= patterns)

    val orgs = new TableIo[OrgRow](dumpDir+"/orgs"+dumpSuffix).load
    if (orgs.nonEmpty) actions += (OrgsTQ.rows ++= orgs)

    return actions.toList
  }
}

class TableIo[T](val filename: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Writes a table to a file in json format */
  def dump(rows: Seq[T]) = {
    // read[Map[String,String]](softwareVersions)
    val file = new File(filename)
    file.getParentFile.mkdirs()
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
