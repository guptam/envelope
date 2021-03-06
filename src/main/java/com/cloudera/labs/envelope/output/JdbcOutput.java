/**
 * Copyright © 2016-2017 Cloudera, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.labs.envelope.output;


import com.cloudera.labs.envelope.plan.MutationType;
import com.typesafe.config.Config;
import com.google.common.collect.Sets;
import org.apache.spark.sql.DataFrame;
import scala.Tuple2;

import java.util.List;
import java.util.Properties;
import java.util.Set;

public class JdbcOutput implements BulkOutput  {


  public static final String JDBC_CONFIG_URL = "url";
  public static final String JDBC_CONFIG_TABLENAME = "tablename";
  public static final String JDBC_CONFIG_USERNAME = "username";
  public static final String JDBC_CONFIG_PASSWORD = "password";

  private Config config;

  @Override
  public void configure(Config config) {

    this.config = config;

    if (!config.hasPath(JDBC_CONFIG_URL)) {
      throw new RuntimeException("JDBC output requires '" + JDBC_CONFIG_URL + "' property");
    }

    if (!config.hasPath(JDBC_CONFIG_TABLENAME)) {
      throw new RuntimeException("JDBC output requires '" + JDBC_CONFIG_TABLENAME + "' property");
    }

    if (!config.hasPath(JDBC_CONFIG_USERNAME)) {
      throw new RuntimeException("JDBC output requires '" + JDBC_CONFIG_USERNAME + "' property");
    }

    if (!config.hasPath(JDBC_CONFIG_PASSWORD)) {
      throw new RuntimeException("JDBC output requires '" + JDBC_CONFIG_PASSWORD + "' property");
    }
  }

  @Override
  public Set<MutationType> getSupportedBulkMutationTypes() {
    return Sets.newHashSet(MutationType.INSERT);
  }

  @Override
  public void applyBulkMutations(List<Tuple2<MutationType, DataFrame>> planned) throws Exception {
    String url = config.getString(JDBC_CONFIG_URL);
    String tablename = config.getString(JDBC_CONFIG_TABLENAME);
    String username = config.getString(JDBC_CONFIG_USERNAME);
    String password = config.getString(JDBC_CONFIG_PASSWORD);
    Properties properties = new Properties();
    properties.put("user",username);
    properties.put("password",password);

    for (Tuple2<MutationType, DataFrame> plan : planned) {
      MutationType mutationType = plan._1();
      DataFrame mutation = plan._2();
      switch (mutationType) {
        case INSERT:
          mutation.write().jdbc(url, tablename, properties);
          break;
        default:
          throw new RuntimeException("JDBC output does not support mutation type: " + mutationType);
      }

    }
  }
}
