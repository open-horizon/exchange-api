# Update node 1 as node
source `dirname $0`/../../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_NODEAUTH" -d '{
  "connectivity": {
     "firmware.bluehorizon.network": true,
      "images.bluehorizon.network": true
   },
  "services": [
    {
      "agreementId": "78d7912aafb6c11b7a776f77d958519a6dc718b9bd3da36a1442ebb18fe9da30",
      "serviceUrl":"https://bluehorizon.network/workloads/location",
      "orgid":"ling.com",
      "version":"1.2",
      "arch":"amd64",
      "containers": [
        {
          "name": "/dc23c045eb64e1637d027c4b0236512e89b2fddd3f06290c7b2354421d9d8e0d-location",
          "image": "summit.hovitos.engineering/x86/location:v1.2",
          "created": 1506086099,
          "state": "running"
        }
      ]
    }
  ]
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/nodes/n1/status | $parse
