package org.openhorizon.exchangeapi.table.service

class Service(var owner: String,
              var label: String,
              var description: String,
              var public: Boolean,
              var documentation: String,
              var url: String,
              var version: String,
              var arch: String,
              var sharable: String,
              var matchHardware: Map[String, Any],
              var requiredServices: List[ServiceRef],
              var userInput: List[Map[String, String]],
              var deployment: String,
              var deploymentSignature: String,
              var clusterDeployment: String,
              var clusterDeploymentSignature: String,
              var imageStore: Map[String, Any],
              var lastUpdated: String) {
  def copy = new Service(owner, label, description, public, documentation, url, version, arch, sharable, matchHardware, requiredServices, userInput, deployment, deploymentSignature, clusterDeployment, clusterDeploymentSignature, imageStore, lastUpdated)
}
