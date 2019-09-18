#!/bin/bash

# Reduce/summarize the node output files from scaledriver.sh

if [[ "$1" == "-h" ]]; then
	echo "Usage: $(basename $0)"
	exit 1
fi

# default of where to write the summary or error msgs. Can be overridden
EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"

dir=`dirname $0`

function checkexitcode {
	if [[ $1 == 0 ]]; then return; fi
    # write error msg to both the summary file and stderr
    echo "Summarize:===============> command $2 failed with exit code $1, exiting."
    exit 3
    #if [[ "$3" != 'continue' ]]; then exit $1; fi
}

#cd $EX_PERF_REPORT_DIR

# Show the agbot full summary files
head -n 100 $EX_PERF_REPORT_DIR/*/agbot/*.summary
checkexitcode $? "head -n 100 $EX_PERF_REPORT_DIR/*/agbot/*.summary"

# Show just the critical lines from the node summaries, grouped by host
for d in $EX_PERF_REPORT_DIR/*/node; do
    echo ''
    grep -v -E "^(Simulated |Start time: )" $d/*.summary
    checkexitcode $? "grep -v -E \"^(Simulated |Start time: )\" $d/*.summary"
done
