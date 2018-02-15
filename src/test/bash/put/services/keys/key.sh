# Adds a service
source `dirname $0`/../../../functions.sh '' '' $*

curl $copts -X PUT -H 'Content-Type: text/plain' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '-----BEGIN CERTIFICATE-----
MIII+jCCBOKgAwIBAgIUEfeMrmSFxCUKATcNPcowfs/lU9owDQYJKoZIhvcNAQEL
BQAwJjEMMAoGA1UEChMDaWJtMRYwFAYDVQQDDA1icEB1cy5pYm0uY29tMB4XDTE4
MDEwMjAxNDkyMFoXDTIyMDEwMjEzNDgzMFowJjEMMAoGA1UEChMDaWJtMRYwFAYD
VQQDDA1icEB1cy5pYm0uY29tMIIEIjANBgkqhkiG9w0BAQEFAAOCBA8AMIIECgKC
-----END CERTIFICATE-----
' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/services/bluehorizon.network-services-gps_1.2.3_amd64/keys/mykey3.pem | $parse
