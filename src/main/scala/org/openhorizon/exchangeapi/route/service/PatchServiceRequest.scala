package org.openhorizon.exchangeapi.route.service

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.service.{ServiceRef, ServicesTQ}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatchServiceRequest(label: Option[String],
                                     description: Option[String],
                                     public: Option[Boolean],
                                     documentation: Option[String],
                                     url: Option[String],
                                     version: Option[String],
                                     arch: Option[String],
                                     sharable: Option[String],
                                     matchHardware: Option[Map[String,Any]],
                                     requiredServices: Option[List[ServiceRef]],
                                     userInput: Option[List[Map[String,String]]],
                                     deployment: Option[String],
                                     deploymentSignature: Option[String],
                                     clusterDeployment: Option[String],
                                     clusterDeploymentSignature: Option[String],
                                     imageStore: Option[Map[String,Any]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ None
  }

  /** Returns a tuple of the db action to update parts of the service, and the attribute name being updated. */
  def getDbUpdate(service: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this service
    label match { case Some(lab) => return ((for { d <- ServicesTQ if d.service === service } yield (d.service,d.label,d.lastUpdated)).update((service, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- ServicesTQ if d.service === service } yield (d.service,d.description,d.lastUpdated)).update((service, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- ServicesTQ if d.service === service } yield (d.service,d.public,d.lastUpdated)).update((service, pub, lastUpdated)), "public"); case _ => ; }
    documentation match { case Some(doc) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.documentation,d.lastUpdated)).update((service, doc, lastUpdated)), "documentation"); case _ => ; }
    url match { case Some(u) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.url,d.lastUpdated)).update((service, u, lastUpdated)), "url"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- ServicesTQ if d.service === service } yield (d.service,d.version,d.lastUpdated)).update((service, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- ServicesTQ if d.service === service } yield (d.service,d.arch,d.lastUpdated)).update((service, ar, lastUpdated)), "arch"); case _ => ; }
    sharable match { case Some(share) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.sharable,d.lastUpdated)).update((service, share, lastUpdated)), "sharable"); case _ => ; }
    matchHardware match { case Some(mh) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.matchHardware,d.lastUpdated)).update((service, write(mh), lastUpdated)), "matchHardware"); case _ => ; }
    requiredServices match { case Some(rs) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.requiredServices,d.lastUpdated)).update((service, write(rs), lastUpdated)), "requiredServices"); case _ => ; }
    userInput match { case Some(ui) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.userInput,d.lastUpdated)).update((service, write(ui), lastUpdated)), "userInput"); case _ => ; }
    deployment match { case Some(dep) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.deployment,d.lastUpdated)).update((service, dep, lastUpdated)), "deployment"); case _ => ; }
    deploymentSignature match { case Some(depsig) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.deploymentSignature,d.lastUpdated)).update((service, depsig, lastUpdated)), "deploymentSignature"); case _ => ; }
    imageStore match { case Some(p) => return ((for {d <- ServicesTQ if d.service === service } yield (d.service,d.imageStore,d.lastUpdated)).update((service, write(p), lastUpdated)), "imageStore"); case _ => ; }
    (null, null)
  }
}
