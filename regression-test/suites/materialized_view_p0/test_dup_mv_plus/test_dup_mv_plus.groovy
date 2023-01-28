// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import org.codehaus.groovy.runtime.IOGroovyMethods

suite ("test_dup_mv_plus") {
    sql """ DROP TABLE IF EXISTS d_table; """

    sql """
            create table d_table(
                k1 int null,
                k2 int not null,
                k3 bigint null,
                k4 varchar(100) null
            )
            duplicate key (k1,k2,k3)
            distributed BY hash(k1) buckets 3
            properties("replication_num" = "1");
        """

    sql "insert into d_table select 1,1,1,'a';"
    sql "insert into d_table select 2,2,2,'b';"
    sql "insert into d_table select 3,-3,null,'c';"

    def result = "null"
    sql "create materialized view k12p as select k1,k2+1 from d_table;"
    while (!result.contains("FINISHED")){
        result = sql "SHOW ALTER TABLE MATERIALIZED VIEW WHERE TableName='d_table' ORDER BY CreateTime DESC LIMIT 1;"
        result = result.toString()
        logger.info("result: ${result}")
        if(result.contains("CANCELLED")){
            return 
        }
        Thread.sleep(1000)
    }

    sql "insert into d_table select -4,-4,-4,'d';"

    qt_select_star "select * from d_table order by k1;"

    explain {
        sql("select k1,k2+1 from d_table order by k1;")
        contains "(k12p)"
    }
    qt_select_mv "select k1,k2+1 from d_table order by k1;"

    explain {
        sql("select k2+1 from d_table order by k1;")
        contains "(k12p)"
    }
    qt_select_mv_sub "select k2+1 from d_table order by k1;"

    explain {
        sql("select k2+1-1 from d_table order by k1;")
        contains "(k12p)"
    }
    qt_select_mv_sub_add "select k2+1-1 from d_table order by k1;"

    explain {
        sql("select sum(k2+1) from d_table group by k1 order by k1;")
        contains "(k12p)"
    }
    qt_select_group_mv "select sum(k2+1) from d_table group by k1 order by k1;"

    explain {
        sql("select sum(k1) from d_table group by k2+1 order by k2+1;")
        contains "(k12p)"
    }
    qt_select_group_mv "select sum(k1) from d_table group by k2+1 order by k2+1;"

    explain {
        sql("select sum(k2+1-1) from d_table group by k1 order by k1;")
        contains "(k12p)"
    }
    qt_select_group_mv_add "select sum(k2+1-1) from d_table group by k1 order by k1;"

    qt_select_group_mv_not "select sum(k2) from d_table group by k3 order by k3;"
}
