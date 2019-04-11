# Run rest api methods as user via https. Most useful for methods that do not need an input body, like GET and DELETE.
source `dirname $0`/functions.sh $1 $2 ${@:3}

HZN_EXCHANGE_URL=https://edge-fab-exchange:8443/v1
CERT_PEM=$(dirname $0)/../../../keys/etc/exchangecert.pem

#echo curl $copts -X $method --cacert ../../../keys/etc/exchangecert.pem -H 'Accept: application/json' -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" $HZN_EXCHANGE_URL/${org}$resource
curl $copts -X $method --cacert $CERT_PEM -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" $HZN_EXCHANGE_URL/${org}$resource | $parse
