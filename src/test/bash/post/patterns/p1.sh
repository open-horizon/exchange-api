# Adds a pattern
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "My Pattern", "description": "blah blah", "public": true,
  "services": [
    {
      "serviceUrl": "https://bluehorizon.network/services/netspeed",
      "serviceOrgid": "'$EXCHANGE_ORG'",
      "serviceArch": "amd64",
      "serviceVersions": [
        {
          "version": "1.0.0",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "a",
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
