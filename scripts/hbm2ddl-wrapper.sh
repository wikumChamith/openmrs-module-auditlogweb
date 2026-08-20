#!/bin/bash
# First boot runs Hibernate with hbm2ddl.auto=update so the Envers schema
# exists before core's first audited write — the module's own table creation
# runs in the activator, which is too late for writes made during core and
# earlier-module startup. Once a boot reaches healthy, a marker on the data
# volume switches every later boot to hbm2ddl.auto=none; from then on the
# module keeps the schema in sync.

# The marker attests to schema living in the DATABASE, so the data volume and
# the database must always be reset together (docker compose down -v).
MARKER=/openmrs/data/.envers-schema-created
HEALTH_URL=http://localhost:8080/openmrs/health/started

if [ -f "$MARKER" ]; then
	echo "hbm2ddl-wrapper: marker found at $MARKER; running with hbm2ddl.auto=none"
	export OMRS_EXTRA_HIBERNATE_HBM2DDL_AUTO=none
else
	echo "hbm2ddl-wrapper: no marker found; running with hbm2ddl.auto=update and waiting for $HEALTH_URL"
	export OMRS_EXTRA_HIBERNATE_HBM2DDL_AUTO=update
	# Double-fork so the poller is orphaned to tini (PID 1), which reaps it —
	# otherwise it would linger as a zombie once it exits.
	( (
		attempts=0
		# /health/started only answers 200 once OpenMRS has fully started, so the
		# marker cannot be written while the startup page or setup wizard is up.
		until curl -sf "$HEALTH_URL" > /dev/null 2>&1; do
			attempts=$((attempts + 1))
			if [ "$attempts" -ge 720 ]; then
				echo "hbm2ddl-wrapper: ERROR: OpenMRS not healthy after $attempts checks (~3 hours); marker not written — the next boot will run hbm2ddl.auto=update again" >&2
				exit 1
			fi
			if [ $((attempts % 20)) -eq 0 ]; then
				echo "hbm2ddl-wrapper: still waiting for $HEALTH_URL ($attempts checks)"
			fi
			sleep 15
		done
		if touch "$MARKER"; then
			echo "hbm2ddl-wrapper: Envers schema boot completed; future boots run with hbm2ddl.auto=none"
		else
			echo "hbm2ddl-wrapper: ERROR: could not write $MARKER — every boot will re-run hbm2ddl.auto=update; check the /openmrs/data volume mount and permissions" >&2
		fi
	) & )
fi

exec /openmrs/startup.sh
