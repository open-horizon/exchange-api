# Run rest api methods from a simulated front end. Most useful for methods that do not need an input body, like GET and DELETE.
source `dirname $0`/functions.sh $2 $3 ${@:4}

# Data power calls us similar to: curl -u '{username}:{password}' 'https://{serviceURL}' -H 'type:{subjectType}' -H 'id:{username}' -H 'orgid:{org}' -H 'issuer:IBM_ID' -H 'Content-Type: application/json'
# type: person (user logged into the dashboard), app (API Key), or dev (device/gateway)
idType=$1
if [[ $idType == "person" ]]; then
    id="feuser"
elif [[ $idType == "app" ]]; then
    id="feapikey"
elif [[ $idType == "dev" ]]; then
    id="fenode"
else
    echo "Error: unknown front end id type: $idType"
    exit 2
fi

curl $copts -X $method -H 'Accept: application/json' -H "type:$idType" -H "id:$id" -H "orgid:$EXCHANGE_ORG" -H "issuer:IBM_ID" $EXCHANGE_URL_ROOT/v1/${org}$resource | $parse