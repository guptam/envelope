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
package com.cloudera.labs.envelope.run;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;

import com.cloudera.labs.envelope.derive.Deriver;
import com.cloudera.labs.envelope.derive.DeriverFactory;
import com.cloudera.labs.envelope.input.Input;
import com.cloudera.labs.envelope.input.InputFactory;
import com.cloudera.labs.envelope.output.BulkOutput;
import com.cloudera.labs.envelope.output.Output;
import com.cloudera.labs.envelope.output.OutputFactory;
import com.cloudera.labs.envelope.output.RandomOutput;
import com.cloudera.labs.envelope.plan.BulkPlanner;
import com.cloudera.labs.envelope.plan.MutationType;
import com.cloudera.labs.envelope.plan.PlannedRow;
import com.cloudera.labs.envelope.plan.Planner;
import com.cloudera.labs.envelope.plan.PlannerFactory;
import com.cloudera.labs.envelope.plan.RandomPlanner;
import com.cloudera.labs.envelope.utils.RowUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import scala.Tuple2;

/**
 * A data step is a step that will contain a DataFrame that other steps can use.
 * The DataFrame can be created either by an input or a deriver.
 * The DataFrame can be optionally written to an output, as planned by the planner.
 */
public abstract class DataStep extends Step {

  public static final String CACHE_PROPERTY = "cache";
  public static final String SMALL_HINT_PROPERTY = "hint.small";

  protected boolean finished = false;
  protected DataFrame data;
  protected Input input;
  protected Deriver deriver;
  protected Output output;

  public DataStep(String name, Config config) throws Exception {
    super(name, config);

    if (hasInput() && hasDeriver()) {
      throw new RuntimeException("Steps can not have both an input and a deriver");
    }

    if (hasInput()) {
      Config inputConfig = config.getConfig("input");
      input = InputFactory.create(inputConfig);
    }
    if (hasDeriver()) {
      Config deriverConfig = config.getConfig("deriver");
      deriver = DeriverFactory.create(deriverConfig);
    }
    if (hasOutput()) {
      Config outputConfig = config.getConfig("output");
      output = OutputFactory.create(outputConfig);
    }
  }

  public boolean hasFinished() {
    return finished;
  }

  public void setFinished(boolean finished) {
    this.finished = finished;
  }

  public DataFrame getData() {
    return data;  
  }

  public void setData(DataFrame data) throws Exception {
    this.data = data;

    data.registerTempTable(getName());

    if (doesCache()) {
      cache();
    }

    if (usesSmallHint()) {
      applySmallHint();
    }

    if (hasOutput()) {
      writeOutput();
    }
  }

  private boolean doesCache() {
    if (!config.hasPath(CACHE_PROPERTY)) return true;

    return config.getBoolean(CACHE_PROPERTY);
  }

  private void cache() {
    data.persist(StorageLevel.MEMORY_ONLY());
  }

  public void clearCache() {
    data.unpersist(false);
  }

  private boolean usesSmallHint() {
    if (!config.hasPath(SMALL_HINT_PROPERTY)) return false;

    return config.getBoolean(SMALL_HINT_PROPERTY);
  }

  private void applySmallHint() {
    data = functions.broadcast(data);
  }

  protected Map<String, DataFrame> getStepDataFrames(Set<Step> steps) {
    Map<String, DataFrame> stepDFs = Maps.newHashMap();

    for (Step step : steps) {
      if (step instanceof DataStep) {
        stepDFs.put(step.getName(), ((DataStep)step).getData());
      }
    }

    return stepDFs;
  }

  public boolean hasInput() {
    return config.hasPath("input");
  }

  public boolean hasDeriver() {
    return config.hasPath("deriver");
  }

  public boolean hasOutput() {
    return config.hasPath("output");
  }

  private void writeOutput() throws Exception {
    Config plannerConfig = config.getConfig("planner");
    Planner planner = PlannerFactory.create(plannerConfig);
    validatePlannerOutputCompatibility(planner, output);

    // Plan the mutations, and then apply them to the output, based on the type of planner used
    if (planner instanceof RandomPlanner) {      
      RandomPlanner randomPlanner = (RandomPlanner)planner;
      List<String> keyFieldNames = randomPlanner.getKeyFieldNames();
      Config outputConfig = config.getConfig("output");
      JavaRDD<PlannedRow> planned = planMutationsByKey(data, keyFieldNames, plannerConfig, outputConfig);

      applyMutations(planned, outputConfig);
    }
    else if (planner instanceof BulkPlanner) {
      BulkPlanner bulkPlanner = (BulkPlanner)planner;
      List<Tuple2<MutationType, DataFrame>> planned = bulkPlanner.planMutationsForSet(data);

      BulkOutput bulkOutput = (BulkOutput)output;      
      bulkOutput.applyBulkMutations(planned);
    }
    else {
      throw new RuntimeException("Unexpected output class: " + output.getClass().getName());
    }
  }

  private void validatePlannerOutputCompatibility(Planner planner, Output output) {
    Set<MutationType> plannerMTs = planner.getEmittedMutationTypes();

    if (planner instanceof RandomPlanner) {
      if (!(output instanceof RandomOutput)) {
        handleIncompatiblePlannerOutput(planner, output);
      }

      Set<MutationType> outputMTs = ((RandomOutput)output).getSupportedRandomMutationTypes();

      for (MutationType planMT : plannerMTs) {
        if (!outputMTs.contains(planMT)) {
          handleIncompatiblePlannerOutput(planner, output);
        }
      }
    }
    else if (planner instanceof BulkPlanner) {
      if (!(output instanceof BulkOutput)) {
        handleIncompatiblePlannerOutput(planner, output);
      }

      Set<MutationType> outputMTs = ((BulkOutput)output).getSupportedBulkMutationTypes();

      for (MutationType planMT : plannerMTs) {
        if (!outputMTs.contains(planMT)) {
          handleIncompatiblePlannerOutput(planner, output);
        }
      }
    }
    else {
      throw new RuntimeException("Unexpected planner class: " + planner.getClass().getName());
    }
  }

  private void handleIncompatiblePlannerOutput(Planner planner, Output output) {
    throw new RuntimeException("Incompatible planner (" + planner.getClass() + ") and output (" + output.getClass() + ").");
  }

  // Group the arriving records by key, attach the existing records for each key, and plan
  private JavaRDD<PlannedRow> planMutationsByKey(DataFrame arriving, List<String> keyFieldNames, Config plannerConfig, Config outputConfig) {
    JavaPairRDD<Row, Iterable<Row>> arrivingByKey = 
        arriving.javaRDD().groupBy(new ExtractKeyFunction(keyFieldNames));

    JavaPairRDD<Row, Tuple2<Iterable<Row>, Iterable<Row>>> arrivingAndExistingByKey =
        arrivingByKey.mapPartitionsToPair(new JoinExistingForKeysFunction(outputConfig, keyFieldNames));

    JavaRDD<PlannedRow> planned = 
        arrivingAndExistingByKey.flatMap(new PlanForKeyFunction(plannerConfig));

    return planned;
  }

  @SuppressWarnings("serial")
  private static class ExtractKeyFunction implements Function<Row, Row> {
    private StructType schema;
    private List<String> keyFieldNames;

    public ExtractKeyFunction(List<String> keyFieldNames) {
      this.keyFieldNames = keyFieldNames;
    }

    @Override
    public Row call(Row arrived) throws Exception {
      if (schema == null) {
        schema = RowUtils.subsetSchema(arrived.schema(), keyFieldNames);
      }

      Row key = RowUtils.subsetRow(arrived, schema);

      return key;
    }
  };

  @SuppressWarnings("serial")
  private static class JoinExistingForKeysFunction
  implements PairFlatMapFunction<Iterator<Tuple2<Row, Iterable<Row>>>, Row, Tuple2<Iterable<Row>, Iterable<Row>>> {
    private Config outputConfig;
    private RandomOutput output;
    private List<String> keyFieldNames;

    public JoinExistingForKeysFunction(Config outputConfig, List<String> keyFieldNames) {
      this.outputConfig = outputConfig;
      this.keyFieldNames = keyFieldNames;
    }

    // Add the existing records for the keys to the arriving records
    @Override
    public Iterable<Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>>
    call(Iterator<Tuple2<Row, Iterable<Row>>> arrivingForKeysIterator) throws Exception
    {
      // If there are no arriving keys, return an empty list
      if (!arrivingForKeysIterator.hasNext()) {
        return Lists.newArrayList();
      }

      // If we have not instantiated the output for this partition, instantiate it
      if (output == null) {
        output = (RandomOutput)OutputFactory.create(outputConfig);
      }

      // Convert the iterator of keys to a list
      List<Tuple2<Row, Iterable<Row>>> arrivingForKeys = Lists.newArrayList(arrivingForKeysIterator);

      // Extract the keys from the keyed arriving records
      Set<Row> arrivingKeys = extractKeys(arrivingForKeys);

      // Get the existing records for those keys from the output
      Iterable<Row> existingWithoutKeys = output.getExistingForFilters(arrivingKeys);
      
      // Map the retrieved existing records to the keys they were looked up from
      Map<Row, Iterable<Row>> existingForKeys = mapExistingToKeys(existingWithoutKeys);

      // Attach the existing records by key to the arriving records by key
      List<Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>> arrivingAndExistingForKeys = 
          attachExistingToArrivingForKeys(existingForKeys, arrivingForKeys);

      return arrivingAndExistingForKeys;
    }

    private Set<Row> extractKeys(List<Tuple2<Row, Iterable<Row>>> arrivingForKeys) {
      Set<Row> arrivingKeys = Sets.newHashSet();

      for (Tuple2<Row, Iterable<Row>> arrivingForKey : arrivingForKeys) {
        arrivingKeys.add(arrivingForKey._1());
      }

      return arrivingKeys;
    }

    private Map<Row, Iterable<Row>> mapExistingToKeys(Iterable<Row> existingWithoutKeys) throws Exception {
      Map<Row, Iterable<Row>> existingForKeys = Maps.newHashMap();
      ExtractKeyFunction extractKeyFunction = new ExtractKeyFunction(keyFieldNames);

      for (Row existing : existingWithoutKeys) {
        Row existingKey = extractKeyFunction.call(existing);

        if (!existingForKeys.containsKey(existingKey)) {
          existingForKeys.put(existingKey, Lists.<Row>newArrayList());
        }

        ((List<Row>)existingForKeys.get(existingKey)).add(existing);
      }

      return existingForKeys;
    }

    private List<Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>> attachExistingToArrivingForKeys
    (Map<Row, Iterable<Row>> existingForKeys, List<Tuple2<Row, Iterable<Row>>> arrivingForKeys)
    {
      List<Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>> arrivingAndExistingForKeys = Lists.newArrayList();
      for (Tuple2<Row, Iterable<Row>> arrivingForKey : arrivingForKeys) {
        Row key = arrivingForKey._1();
        Iterable<Row> arriving = arrivingForKey._2();

        Iterable<Row> existing;
        if (existingForKeys.containsKey(key)) {
          existing = existingForKeys.get(key);
        }
        else {
          existing = Lists.newArrayList();
        }

        // Oh my...
        Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>> arrivingAndExistingForKey = 
            new Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>(key, 
                new Tuple2<Iterable<Row>, Iterable<Row>>(arriving, existing));

        arrivingAndExistingForKeys.add(arrivingAndExistingForKey);
      }

      return arrivingAndExistingForKeys;
    }
  }

  @SuppressWarnings("serial")
  private static class PlanForKeyFunction
  implements FlatMapFunction<Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>>, PlannedRow> {
    private Config config;
    private RandomPlanner planner;

    public PlanForKeyFunction(Config config) {
      this.config = config;
    }

    @Override
    public Iterable<PlannedRow>
    call(Tuple2<Row, Tuple2<Iterable<Row>, Iterable<Row>>> keyedRecords) throws Exception {
      if (planner == null) {
        planner = (RandomPlanner)PlannerFactory.create(config);
      }

      Row key = keyedRecords._1();
      List<Row> arrivingRecords = Lists.newArrayList(keyedRecords._2()._1());
      List<Row> existingRecords = Lists.newArrayList(keyedRecords._2()._2());

      Iterable<PlannedRow> plannedForKey = planner.planMutationsForKey(key, arrivingRecords, existingRecords);

      return plannedForKey;
    }
  };

  private void applyMutations(JavaRDD<PlannedRow> planned, Config outputConfig) {
    planned.foreachPartition(new ApplyMutationsForPartitionFunction(outputConfig));
  }

  @SuppressWarnings("serial")
  private static class ApplyMutationsForPartitionFunction implements VoidFunction<Iterator<PlannedRow>> {
    private Config config;
    private RandomOutput output;

    public ApplyMutationsForPartitionFunction(Config config) {
      this.config = config;
    }

    @Override
    public void call(Iterator<PlannedRow> t) throws Exception {
      if (output == null) {
        output = (RandomOutput)OutputFactory.create(config);
      }

      output.applyRandomMutations(Lists.newArrayList(t));
    }
  }

}
