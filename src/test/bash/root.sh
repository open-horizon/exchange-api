# Run rest api methods as root
source `dirname $0`/functions.sh $1 $2 ${@:3}

curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $HZN_EXCHANGE_URL/${org}$resource | $parse
