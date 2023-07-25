package org.openhorizon.exchangeapi.table.deploymentpattern

class Pattern(var owner: String,
              var label: String,
              var description: String,
              var public: Boolean,
              var services: List[PServices],
              var userInput: List[OneUserInputService],
              var secretBinding: List[OneSecretBindingService],
              var agreementProtocols: List[Map[String,String]],
              var lastUpdated: String,
              var clusterNamespace: String = "") {
  def copy =
    new Pattern(agreementProtocols = agreementProtocols,
                clusterNamespace = clusterNamespace,
                description = description,
                label = label,
                lastUpdated = lastUpdated,
                owner = owner,
                public = public,
                secretBinding = secretBinding,
                services = services,
                userInput = userInput)
}
