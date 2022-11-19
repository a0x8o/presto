#!/usr/bin/env bash

set -euo pipefail -x

. ${BASH_SOURCE%/*}/common.sh

test -v ADL_NAME
test -v ADL_CLIENT_ID
test -v ADL_CREDENTIAL
test -v ADL_REFRESH_URL

start_docker_containers

# insert Azure credentials
# TODO replace core-site.xml.adl-template with apply-site-xml-override.sh
exec_in_hadoop_master_container cp /docker/files/core-site.xml.adl-template /etc/hadoop/conf/core-site.xml
exec_in_hadoop_master_container sed -i \
    -e "s|%ADL_CLIENT_ID%|${ADL_CLIENT_ID}|g" \
    -e "s|%ADL_CREDENTIAL%|${ADL_CREDENTIAL}|g" \
    -e "s|%ADL_REFRESH_URL%|${ADL_REFRESH_URL}|g" \
    /etc/hadoop/conf/core-site.xml

# create test table
table_path="adl://${ADL_NAME}.azuredatalakestore.net/presto_test_external_fs_2/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /tmp/test_table.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "CREATE EXTERNAL TABLE presto_test_external_fs(t_bigint bigint) LOCATION '${table_path}'"

table_path="adl://${ADL_NAME}.azuredatalakestore.net/presto_test_external_fs_with_header/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /docker/files/test_table_with_header.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "
    CREATE EXTERNAL TABLE presto_test_external_fs_with_header(t_bigint bigint)
    STORED AS TEXTFILE
    LOCATION '${table_path}'
    TBLPROPERTIES ('skip.header.line.count'='1')"

table_path="adl://${ADL_NAME}.azuredatalakestore.net/presto_test_external_fs_with_header_and_footer/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /docker/files/test_table_with_header_and_footer.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "
    CREATE EXTERNAL TABLE presto_test_external_fs_with_header_and_footer(t_bigint bigint)
    STORED AS TEXTFILE
    LOCATION '${table_path}'
    TBLPROPERTIES ('skip.header.line.count'='2', 'skip.footer.line.count'='2')"

stop_unnecessary_hadoop_services

# restart hive-metastore to apply adl changes in core-site.xml
docker exec "$(hadoop_master_container)" supervisorctl restart hive-metastore
retry check_hadoop

pushd $PROJECT_ROOT
set +e
./mvnw -B -pl presto-hive-hadoop2 test -P test-hive-hadoop2-adl \
    -DHADOOP_USER_NAME=hive \
    -Dhive.hadoop2.metastoreHost=localhost \
    -Dhive.hadoop2.metastorePort=9083 \
    -Dhive.hadoop2.databaseName=default \
    -Dhive.hadoop2.adl-name=${ADL_NAME} \
    -Dhive.hadoop2.adl-client-id=${ADL_CLIENT_ID} \
    -Dhive.hadoop2.adl-credential=${ADL_CREDENTIAL} \
    -Dhive.hadoop2.adl-refresh-url=${ADL_REFRESH_URL}
EXIT_CODE=$?
set -e
popd

cleanup_docker_containers

exit ${EXIT_CODE}
