package org.openhorizon.exchangeapi.route.deploymentpolicy

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BService, BusinessPoliciesTQ}
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatchBusinessPolicyRequest(label: Option[String],
                                            description: Option[String],
                                            service: Option[BService],
                                            userInput: Option[List[OneUserInputService]],
                                            secretBinding:Option[List[OneSecretBindingService]] ,
                                            properties: Option[List[OneProperty]],
                                            constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ if (service.isDefined) BusinessUtils.getAnyProblem(service.get)
    else None
  }

  /** Returns a tuple of the db action to update parts of the businessPolicy, and the attribute name being updated. */
  def getDbUpdate(businessPolicy: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this businessPolicy
    constraints match { case Some(con) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.constraints,d.lastUpdated)).update((businessPolicy, write(con), lastUpdated)), "constraints"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.description,d.lastUpdated)).update((businessPolicy, desc, lastUpdated)), "description"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.label,d.lastUpdated)).update((businessPolicy, lab, lastUpdated)), "label"); case _ => ; }
    properties match { case Some(prop) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.properties,d.lastUpdated)).update((businessPolicy, write(prop), lastUpdated)), "properties"); case _ => ; }
    secretBinding match {case Some(bind) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.secretBinding,d.lastUpdated)).update((businessPolicy, write(bind), lastUpdated)), "secretBinding"); case _ => ; }
    service match { case Some(svc) => return ((for {d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.service,d.lastUpdated)).update((businessPolicy, write(svc), lastUpdated)), "service"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.userInput,d.lastUpdated)).update((businessPolicy, write(input), lastUpdated)), "userInput"); case _ => ; }
    (null, null)
  }
}
