# Adds a workload
source `dirname $0`/../../functions.sh POST $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "My Pattern", "description": "blah blah", "public": true,
  "workloads": [
    {
      "workloadUrl": "https://bluehorizon.network/workloads/netspeed",
      "workloadOrgid": "IBM",
      "workloadArch": "amd64",
      "workloadVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
          "priority": {},
          "upgradePolicy": {}
        }
      ],
      "dataVerification": {},
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
  "agreementProtocols": [{ "name": "Basic" }]
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/patterns/p1 | $parse
