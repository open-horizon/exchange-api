package org.openhorizon.exchangeapi.table.service

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read

case class Service(arch: String,
                   clusterDeployment: String,
                   clusterDeploymentSignature: String,
                   deployment: String,
                   deploymentSignature: String,
                   description: String,
                   documentation: String,
                   imageStore: Map[String, Any],
                   label: String,
                   lastUpdated: String,
                   matchHardware: Map[String, Any] = Map.empty[String, Any],
                   organization: String,
                   owner: String,
                   public: Boolean,
                   requiredServices: List[ServiceRef] = List.empty[ServiceRef],
                   sharable: String,
                   url: String,
                   userInput: List[Map[String, String]] = List.empty[Map[String, String]],
                   version: String) {
  def this(tuple: (String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   String,
                   Boolean,
                   String,
                   String,
                   String,
                   String,
                   String))(implicit formats: Formats) =
    this(arch = tuple._1,
         clusterDeployment = tuple._2,
         clusterDeploymentSignature = tuple._3,
         deployment = tuple._4,
         deploymentSignature = tuple._5,
         description = tuple._6,
         documentation = tuple._7,
         imageStore = if (tuple._8 != "") read[Map[String, Any]](tuple._8) else Map[String, Any](),
         label = tuple._9,
         lastUpdated = tuple._10,
         matchHardware = if (tuple._11 != "") read[Map[String, Any]](tuple._11) else Map[String, Any](),
         organization = tuple._12,
         owner = tuple._13,
         public = tuple._14,
         requiredServices =
           if (tuple._15 != "")
             (read[List[ServiceRef]](tuple._15)).map(
               sr =>
                 ServiceRef(sr.url, sr.org, sr.version, (if (sr.versionRange.isEmpty) sr.version else sr.versionRange), sr.arch))
           else
             List[ServiceRef](),
         sharable = tuple._16,
         url = tuple._17,
         userInput = if (tuple._18 != "") read[List[Map[String, String]]](tuple._18) else List[Map[String, String]](),
         version = tuple._19)
}
