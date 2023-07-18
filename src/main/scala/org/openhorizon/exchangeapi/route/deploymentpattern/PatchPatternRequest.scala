package org.openhorizon.exchangeapi.route.deploymentpattern

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService, PServices, PatternsTQ}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatchPatternRequest(label: Option[String],
                                     description: Option[String],
                                     public: Option[Boolean],
                                     services: Option[List[PServices]],
                                     userInput: Option[List[OneUserInputService]],
                                     secretBinding:Option[List[OneSecretBindingService]],
                                     agreementProtocols: Option[List[Map[String,String]]],
                                     clusterNamespace: Option[String] = None) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ if (services.isDefined) PatternUtils.validatePatternServices(services.get)
    else None
  }

  /** Returns a tuple of the db action to update parts of the pattern, and the attribute name being updated. */
  def getDbUpdate(pattern: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this pattern
    agreementProtocols match { case Some(ap) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.agreementProtocols,d.lastUpdated)).update((pattern, write(ap), lastUpdated)), "agreementProtocols"); case _ => ; }
    clusterNamespace match { case Some(namespace) => return ((for { d <- PatternsTQ if d.clusterNamespace === namespace } yield (d.pattern,d.clusterNamespace,d.lastUpdated)).update((pattern, Option(namespace), lastUpdated)), "clusterNamespace"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.description,d.lastUpdated)).update((pattern, desc, lastUpdated)), "description"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.label,d.lastUpdated)).update((pattern, lab, lastUpdated)), "label"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.public,d.lastUpdated)).update((pattern, pub, lastUpdated)), "public"); case _ => ; }
    secretBinding match {case Some(bind) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.secretBinding,d.lastUpdated)).update((pattern, write(bind), lastUpdated)), "secretBinding"); case _ => ; }
    services match { case Some(svc) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.services,d.lastUpdated)).update((pattern, write(svc), lastUpdated)), "services"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- PatternsTQ if d.pattern === pattern } yield (d.pattern,d.userInput,d.lastUpdated)).update((pattern, write(input), lastUpdated)), "userInput"); case _ => ; }
    (null, null)
  }

}
