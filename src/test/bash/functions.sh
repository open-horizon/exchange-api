# Meant to be sourced by the exchange manual test scripts

method=$(echo $1 | awk '{print toupper($0)}')

resource=${2#/}     # remove leading slash in case there, because we will add it below
if [[ ${resource:0:6} == "admin/" || ${resource:0:4} == "orgs" ]]; then
  org=""     # the admin methods are not under the org resource
else
  org="orgs/$EXCHANGE_ORG/"
fi

if [[ -z $parse ]]; then
	if [[ $3 == "-r" ]]; then
		parse=cat
		copts='-sS -w %{http_code}'
	elif [[ $3 == "-rr" ]]; then
		parse=cat
		copts='-sS'
	else
		parse="jq ."
		copts='-sS -w %{http_code}'
	fi
else
	copts='-s'
fi