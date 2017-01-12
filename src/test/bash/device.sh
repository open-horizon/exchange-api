# Gets all users as root
source `dirname $0`/functions.sh $1 ${@:3}

curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_DEVAUTH" $EXCHANGE_URL_ROOT/v1/$2 | $parse