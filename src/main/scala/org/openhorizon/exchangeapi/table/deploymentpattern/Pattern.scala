package org.openhorizon.exchangeapi.table.deploymentpattern

import org.json4s.Formats
import org.json4s.jackson.Serialization.read

case class Pattern(owner: String,
                   label: String,
                   description: String,
                   public: Boolean,
                   services: List[PServices],
                   userInput: List[OneUserInputService],
                   secretBinding: List[OneSecretBindingService],
                   agreementProtocols: List[Map[String,String]],
                   lastUpdated: String,
                   clusterNamespace: String = "") {
 def this(tuple: (String, Option[String], String, String, String, String, Boolean, String, String, String))(implicit formats: Formats) =
   this(agreementProtocols = if (tuple._1 != "") read[List[Map[String,String]]](tuple._1) else List[Map[String,String]](),
        clusterNamespace = tuple._2.getOrElse(""),
        description = tuple._3,
        label = tuple._4,
        lastUpdated = tuple._5,
        owner = tuple._6,
        public = tuple._7,
        secretBinding = if (tuple._8 != "") read[List[OneSecretBindingService]](tuple._8) else List[OneSecretBindingService](),
        services = if (tuple._9 == "") List[PServices]() else read[List[PServices]](tuple._9),
        userInput = if (tuple._10 != "") read[List[OneUserInputService]](tuple._10) else List[OneUserInputService]())
}
